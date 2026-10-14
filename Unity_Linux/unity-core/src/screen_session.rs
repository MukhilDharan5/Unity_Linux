//! Session-bound screen streams. Keep one JPEG, never a playback backlog.
use super::{Engine, State};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWriteExt, BufReader},
    process::{Child, Command},
    sync::{mpsc, oneshot, watch},
    time::timeout,
};
use unity_protocol::Message;
use unity_transport::SecureStream;

pub const MAX_BYTES: usize = 262144;
#[derive(Clone)]
pub struct ScreenFrame {
    pub sequence: u64,
    pub width: u32,
    pub height: u32,
    pub jpeg: Vec<u8>,
}
pub(super) struct ScreenSession {
    generation: String,
    token: String,
    socket: Option<oneshot::Sender<SecureStream>>,
    pub operation: String,
    pub stop: watch::Sender<bool>,
    done: watch::Receiver<bool>,
    source: String,
    controls: Option<mpsc::Sender<Control>>,
    metadata: Value,
}
pub(super) struct Control {
    id: String,
    action: String,
    data: Value,
    reply: oneshot::Sender<std::result::Result<Value, String>>,
}
pub(super) enum Work {
    Start {
        device: String,
        source: String,
        token: String,
        socket: oneshot::Receiver<SecureStream>,
        cancel: watch::Receiver<bool>,
        done: watch::Sender<bool>,
        control: bool,
        blank_display: bool,
    },
    Stop {
        done: Option<watch::Receiver<bool>>,
    },
    Control {
        device: String,
        action: String,
        data: Value,
    },
}
pub(super) fn cancel_device(state: &mut State, device: &str) {
    if let Some(screen) = state.screens.get(device) {
        screen.stop.send(true).ok();
    }
    state.screen_frames.remove(device);
}
pub(super) fn cancel_generation(state: &mut State, device: &str, generation: &str) {
    if state
        .screens
        .get(device)
        .is_some_and(|s| s.generation == generation)
    {
        cancel_device(state, device);
        if let Some(device) = state.devices.get_mut(device) {
            device.state.remove("screen");
        }
    }
}
pub(super) fn prepare(
    state: &mut State,
    device: &str,
    action: &str,
    data: &Value,
    operation: &str,
) -> Result<Work> {
    if matches!(action, "input" | "display") {
        let screen = state
            .screens
            .get(device)
            .context("No desktop screen share is active")?;
        if screen.source != "desktop"
            || screen.operation != data["session_id"]
            || *screen.stop.borrow()
        {
            bail!("This screen session has ended")
        }
        return Ok(Work::Control {
            device: device.into(),
            action: action.into(),
            data: data.clone(),
        });
    }
    if action == "stop" {
        cancel_device(state, device);
        return Ok(Work::Stop {
            done: state.screens.get(device).map(|s| s.done.clone()),
        });
    }
    if state.screens.contains_key(device) {
        bail!("Stop the current screen share first")
    }
    let generation = state
        .sessions
        .get(device)
        .and_then(|links| links.select(unity_transport::Workload::Bulk))
        .context("device disconnected")?
        .generation
        .clone();
    let token = hex::encode(unity_security::random_bytes());
    let (sender, socket) = oneshot::channel();
    let (stop, cancel) = watch::channel(false);
    let (done, finished) = watch::channel(false);
    let source = data["source"]
        .as_str()
        .context("missing screen source")?
        .to_string();
    let control = source == "desktop" && data["control"] != false;
    let blank_display = data["blank_display"] == true;
    state.screens.insert(
        device.into(),
        ScreenSession {
            generation,
            token: token.clone(),
            socket: Some(sender),
            operation: operation.into(),
            stop,
            done: finished,
            source: source.clone(),
            controls: None,
            metadata: json!({"input_enabled":false,"display_available":false,"display_on":true}),
        },
    );
    state
        .devices
        .get_mut(device)
        .context("unknown device")?
        .state
        .insert(
            "screen".into(),
            json!({"source":source,"state":"starting","session_id":operation}),
        );
    Ok(Work::Start {
        device: device.into(),
        source,
        token,
        socket,
        cancel,
        done,
        control,
        blank_display,
    })
}
pub async fn read_jpeg<R: AsyncRead + Unpin>(reader: &mut R) -> Result<ScreenFrame> {
    let length = reader.read_u32().await? as usize;
    if !(4..=MAX_BYTES).contains(&length) {
        bail!("Screen frame exceeds byte limit")
    }
    let mut jpeg = vec![0; length];
    reader.read_exact(&mut jpeg).await?;
    let (width, height) = unity_plugins::previews::jpeg_dimensions(&jpeg)?;
    if width == 0 || height == 0 || width.max(height) > 1280 || width * height > 921600 {
        bail!("Screen frame exceeds dimension limit")
    }
    Ok(ScreenFrame {
        sequence: 0,
        width: width as u32,
        height: height as u32,
        jpeg,
    })
}
fn capture_path() -> Result<PathBuf> {
    let installed = std::env::current_exe()?.with_file_name("screen-capture.py");
    if installed.is_file() {
        return Ok(installed);
    }
    if cfg!(debug_assertions) {
        let source =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unity-screen/screen-capture.py");
        if source.is_file() {
            return Ok(source);
        }
    }
    bail!("Install the Unity screen capture helper with the daemon")
}
async fn stop_capture(child: &mut Child) {
    if let Some(pid) = child.id() {
        // SIGTERM lets the helper close its portal session; kill_on_drop is the fallback.
        unsafe {
            libc::kill(pid as libc::pid_t, libc::SIGTERM);
        }
        if timeout(Duration::from_secs(2), child.wait()).await.is_err() {
            child.kill().await.ok();
        }
    }
}
impl Engine {
    pub(super) async fn screen_control(
        &self,
        device: &str,
        action: &str,
        data: Value,
    ) -> Result<Value> {
        let commands = {
            let state = self.state.lock().await;
            let d = state.devices.get(device).context("Unknown device")?;
            let screen = state
                .screens
                .get(device)
                .context("No desktop screen share is active")?;
            if !d.connected
                || d.disabled.iter().any(|f| f == "screen")
                || screen.source != "desktop"
                || data["session_id"].as_str() != Some(screen.operation.as_str())
                || *screen.stop.borrow()
                || state
                    .sessions
                    .get(device)
                    .is_none_or(|links| links.generation(&screen.generation).is_none())
            {
                bail!("This screen session has ended")
            }
            if action == "input" && screen.metadata["input_enabled"] != true {
                bail!("Mouse and keyboard access was not approved")
            }
            if action == "display"
                && data["on"] == false
                && (screen.metadata["display_available"] != true
                    || screen.metadata["input_enabled"] != true)
            {
                bail!("Laptop display control requires a live screen share with keyboard and mouse access")
            }
            screen
                .controls
                .clone()
                .context("Desktop screen control is not ready")?
        };
        let (reply, result) = oneshot::channel();
        commands
            .try_send(Control {
                id: uuid::Uuid::new_v4().simple().to_string(),
                action: action.into(),
                data,
                reply,
            })
            .map_err(|_| anyhow::anyhow!("Screen control queue is full or sharing ended"))?;
        timeout(Duration::from_secs(6), result)
            .await
            .context("Desktop screen control timed out")?
            .context("Desktop screen control stopped")?
            .map_err(anyhow::Error::msg)
    }

    async fn screen_metadata(
        &self,
        device: &str,
        op: &str,
        value: &Value,
        tx: &mpsc::Sender<Message>,
    ) {
        let mut state = self.state.lock().await;
        let Some(screen) = state
            .screens
            .get_mut(device)
            .filter(|s| s.operation == op && !*s.stop.borrow())
        else {
            return;
        };
        for key in ["input_enabled", "display_available", "display_on"] {
            if value[key].is_boolean() {
                screen.metadata[key] = value[key].clone();
            }
        }
        if let Some(error) = value["display_error"].as_str() {
            screen.metadata["display_error"] = json!(error.chars().take(512).collect::<String>());
        }
        let metadata = screen.metadata.clone();
        if let Some(d) = state.devices.get_mut(device) {
            if let Some(snapshot) = d.state.get_mut("screen").and_then(Value::as_object_mut) {
                for (key, value) in metadata.as_object().unwrap() {
                    snapshot.insert(key.clone(), value.clone());
                }
            }
        }
        drop(state);
        let mut message = metadata;
        message["request_id"] = json!(op);
        tx.try_send(Message::Command {
            id: uuid::Uuid::new_v4().simple().to_string(),
            feature: "screen".into(),
            action: "state".into(),
            data: message,
        })
        .ok();
        self.changed.send(device.into()).ok();
    }

    pub(super) async fn recover_display(&self) {
        if !self.dir.join("display-backlight.json").exists() {
            return;
        }
        let installed = std::env::current_exe()
            .ok()
            .map(|p| p.with_file_name("display-guard.py"));
        let path = installed.filter(|p| p.is_file()).unwrap_or_else(|| {
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unity-screen/display-guard.py")
        });
        let result = timeout(
            Duration::from_secs(5),
            Command::new("/usr/bin/python3")
                .arg(path)
                .arg("--recover")
                .arg("--state-dir")
                .arg(&self.dir)
                .kill_on_drop(true)
                .output(),
        )
        .await;
        if !matches!(result, Ok(Ok(output)) if output.status.success()) {
            tracing::warn!("Laptop display recovery is still pending");
        }
    }

    pub async fn screen_frame(&self, device: &str) -> Result<ScreenFrame> {
        let state = self.state.lock().await;
        let d = state.devices.get(device).context("unknown device")?;
        if !d.connected || d.disabled.iter().any(|f| f == "screen") {
            bail!("Screen sharing unavailable")
        }
        state
            .screen_frames
            .get(device)
            .cloned()
            .context("No screen frame available")
    }
    pub async fn shutdown_screens(&self) {
        let receivers: Vec<_> = {
            let mut state = self.state.lock().await;
            state.screen_frames.clear();
            state
                .screens
                .values()
                .map(|s| {
                    s.stop.send(true).ok();
                    s.done.clone()
                })
                .collect()
        };
        for mut done in receivers {
            if !*done.borrow() {
                timeout(Duration::from_secs(4), done.changed()).await.ok();
            }
        }
    }
    pub(super) async fn accept_screen(
        &self,
        version: u32,
        device: &str,
        token: &str,
        kind: unity_transport::Kind,
        socket: SecureStream,
    ) -> Result<()> {
        if version != 1 || !unity_protocol::valid_id(device) || token.len() != 64 {
            bail!("Invalid screen identity")
        }
        let mut state = self.state.lock().await;
        let screen = state
            .screens
            .get(device)
            .context("Screen sharing was not requested")?;
        let equal = token
            .bytes()
            .zip(screen.token.bytes())
            .fold(0u8, |a, (b, c)| a | (b ^ c))
            == 0;
        if !equal
            || *screen.stop.borrow()
            || !state.trust.devices.contains_key(device)
            || state.sessions.get(device).is_none_or(|links| {
                links
                    .generation(&screen.generation)
                    .is_none_or(|link| link.kind != kind)
            })
            || state
                .devices
                .get(device)
                .is_none_or(|d| d.disabled.iter().any(|f| f == "screen"))
        {
            bail!("Screen stream is not authorized for this session")
        }
        let bytes: libc::c_int = 65536;
        let result = unsafe {
            libc::setsockopt(
                socket.get_ref().0.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_SNDBUF,
                &bytes as *const _ as *const libc::c_void,
                std::mem::size_of_val(&bytes) as libc::socklen_t,
            )
        };
        if result != 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        state
            .screens
            .get_mut(device)
            .unwrap()
            .socket
            .take()
            .context("Screen token already used")?
            .send(socket)
            .map_err(|_| anyhow::anyhow!("Screen request expired"))?;
        Ok(())
    }
    pub(super) async fn run_screen_operation(
        self: &Arc<Self>,
        op: &str,
        tx: &mpsc::Sender<Message>,
        work: Work,
    ) {
        match work {
            Work::Control {
                device,
                action,
                data,
            } => {
                let result = self.screen_control(&device, &action, data).await;
                match result {
                    Ok(value) => self.finish(op, true, value, String::new()).await,
                    Err(error) => self.finish(op, false, Value::Null, error.to_string()).await,
                }
            }
            Work::Stop { mut done } => {
                let result = if let Some(done) = &mut done {
                    if !*done.borrow() {
                        timeout(Duration::from_secs(5), done.changed())
                            .await
                            .map_err(anyhow::Error::from)
                            .and_then(|r| r.map_err(anyhow::Error::from))
                    } else {
                        Ok(())
                    }
                } else {
                    Ok(())
                };
                self.finish(
                    op,
                    result.is_ok(),
                    json!({"state":"stopped"}),
                    result.err().map(|e| e.to_string()).unwrap_or_default(),
                )
                .await;
            }
            Work::Start {
                device,
                source,
                token,
                socket,
                mut cancel,
                done,
                control,
                blank_display,
            } => {
                self.changed.send(device.clone()).ok();
                let result = async {
                    if *cancel.borrow() { return Ok(()) }
                    timeout(Duration::from_secs(3), tx.send(Message::Command { id: op.into(), feature: "screen".into(), action: "start".into(),
                        data: json!({"request_id":op,"token":token,"source":source,"control":control,"blank_display":blank_display,"format":"jpeg","max_edge":1280,"max_pixels":921600,"max_bytes":MAX_BYTES,"fps":15}) })).await??;
                    let mut socket = tokio::select! {
                        result = timeout(Duration::from_secs(120), socket) => result.context("Phone screen connection or consent timed out")??,
                        _ = cancel.changed() => return Ok(()),
                    };
                    if source == "phone" {
                        unity_transport::write_frame(&mut socket, &Message::Ready).await?;
                        let mut sequence = 0;
                        loop {
                            let mut frame = tokio::select! {
                                result = timeout(Duration::from_secs(if sequence == 0 {120} else {15}), read_jpeg(&mut socket)) => result.context("Phone screen capture stopped")??,
                                _ = cancel.changed() => return Ok(()),
                            };
                            if *cancel.borrow() { return Ok(()) }
                            sequence += 1; frame.sequence = sequence;
                            self.state.lock().await.screen_frames.insert(device.clone(), frame);
                            if sequence == 1 { self.screen_started(op, &device, &source).await; }
                            self.screen_changed.send((device.clone(), sequence)).ok();
                            // Backpressure bounds malicious or misconfigured senders too.
                            tokio::select! { _ = tokio::time::sleep(Duration::from_millis(60)) => {}, _ = cancel.changed() => return Ok(()) }
                        }
                    } else {
                        self.send_desktop_screen(op, &device, &mut socket, &mut cancel, tx, control).await
                    }
                }.await;
                let error = if let Err(error) = result {
                    error.to_string()
                } else {
                    self.state
                        .lock()
                        .await
                        .operations
                        .get(op)
                        .filter(|o| o.status == "failed")
                        .map(|o| o.error.clone())
                        .unwrap_or_default()
                };
                tx.try_send(Message::Command {
                    id: uuid::Uuid::new_v4().simple().to_string(),
                    feature: "screen".into(),
                    action: "stop".into(),
                    data: json!({"error":error}),
                })
                .ok();
                let mut state = self.state.lock().await;
                if state
                    .screens
                    .get(&device)
                    .is_some_and(|s| s.operation == op)
                {
                    let screen = state.screens.remove(&device).unwrap();
                    state.screen_frames.remove(&device);
                    if state
                        .sessions
                        .get(&device)
                        .is_some_and(|links| links.generation(&screen.generation).is_some())
                    {
                        if let Some(d) = state.devices.get_mut(&device).filter(|d| {
                            !d.disabled.iter().any(|f| f == "screen")
                                && d.capabilities.iter().any(|c| {
                                    c.id == "screen"
                                        && c.status == unity_protocol::Availability::Available
                                })
                        }) {
                            d.state.insert("screen".into(), json!({"source":source,"state":if error.is_empty(){"stopped"}else{"failed"},"error":error}));
                        }
                    }
                }
                drop(state);
                self.finish(
                    op,
                    false,
                    Value::Null,
                    if error.is_empty() {
                        "Screen sharing stopped before the first frame".into()
                    } else {
                        error
                    },
                )
                .await;
                self.changed.send(device).ok();
                done.send(true).ok();
            }
        }
    }
    async fn screen_started(&self, op: &str, device: &str, source: &str) {
        self.finish(
            op,
            true,
            json!({"state":"streaming","source":source}),
            String::new(),
        )
        .await;
        let mut state = self.state.lock().await;
        let mut snapshot = state
            .screens
            .get(device)
            .map(|s| s.metadata.clone())
            .unwrap_or_else(|| json!({}));
        snapshot["source"] = json!(source);
        snapshot["state"] = json!("streaming");
        snapshot["session_id"] = json!(op);
        if let Some(d) = state.devices.get_mut(device) {
            d.state.insert("screen".into(), snapshot);
        }
        drop(state);
        self.changed.send(device.into()).ok();
    }
    async fn send_desktop_screen(
        self: &Arc<Self>,
        op: &str,
        device: &str,
        socket: &mut SecureStream,
        cancel: &mut watch::Receiver<bool>,
        tx: &mpsc::Sender<Message>,
        control: bool,
    ) -> Result<()> {
        let mut command = Command::new("/usr/bin/python3");
        command
            .arg(capture_path()?)
            .arg("--state-dir")
            .arg(&self.dir);
        if control {
            command.arg("--control");
        }
        let mut child = command
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()?;
        let mut output = child
            .stdout
            .take()
            .context("missing screen capture output")?;
        let mut input = child.stdin.take().context("missing screen control input")?;
        let errors = child
            .stderr
            .take()
            .context("missing screen control responses")?;
        let (commands, mut controls) = mpsc::channel::<Control>(32);
        if let Some(screen) = self
            .state
            .lock()
            .await
            .screens
            .get_mut(device)
            .filter(|s| s.operation == op)
        {
            screen.controls = Some(commands);
        }
        let pending = Arc::new(tokio::sync::Mutex::new(HashMap::<
            String,
            oneshot::Sender<std::result::Result<Value, String>>,
        >::new()));
        let responses = pending.clone();
        let engine = self.clone();
        let target = device.to_string();
        let request = op.to_string();
        let status_tx = tx.clone();
        let status = tokio::spawn(async move {
            let mut lines = BufReader::new(errors).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(value) = serde_json::from_str::<Value>(&line) {
                    if value["type"] == "state" {
                        engine
                            .screen_metadata(&target, &request, &value, &status_tx)
                            .await;
                    } else if value["type"] == "reply" {
                        if let Some(id) = value["id"].as_str() {
                            if let Some(reply) = responses.lock().await.remove(id) {
                                reply
                                    .send(if value["ok"] == true {
                                        Ok(value["result"].clone())
                                    } else {
                                        Err(value["error"]
                                            .as_str()
                                            .unwrap_or("Screen control failed")
                                            .chars()
                                            .take(512)
                                            .collect())
                                    })
                                    .ok();
                            }
                        }
                    }
                } else {
                    tracing::debug!(message = %line.chars().take(512).collect::<String>(), "Screen capture helper");
                }
            }
        });
        let delivered = Arc::new(AtomicBool::new(false));
        let live = delivered.clone();
        let controller = tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(1));
            loop {
                let line = tokio::select! {
                    message = controls.recv() => {
                        let Some(message) = message else { break };
                        let line = serde_json::to_vec(&json!({"id":message.id,"action":message.action,"data":message.data})).unwrap();
                        pending.lock().await.insert(message.id, message.reply);
                        line
                    }
                    _ = tick.tick() => {
                        if !live.swap(false, Ordering::Relaxed) { continue; }
                        b"{\"action\":\"heartbeat\"}".to_vec()
                    }
                };
                if !matches!(
                    timeout(Duration::from_secs(1), async {
                        input.write_all(&line).await?;
                        input.write_all(b"\n").await
                    })
                    .await,
                    Ok(Ok(()))
                ) {
                    break;
                }
            }
            pending.lock().await.clear();
        });
        let (frames, mut latest) = watch::channel(None);
        let reader = tokio::spawn(async move {
            while let Ok(frame) = read_jpeg(&mut output).await {
                if frames.send(Some(frame)).is_err() {
                    break;
                }
            }
        });
        let result = async {
            tokio::select! {
                result = timeout(Duration::from_secs(120), latest.changed()) => result.context("Desktop screen selection timed out")?.context("Desktop screen capture failed or permission was denied; check the portal and GStreamer installation")?,
                _ = cancel.changed() => return Ok(()),
            }
            unity_transport::write_frame(socket, &Message::Ready).await?;
            let mut started = false;
            loop {
                if *cancel.borrow() { return Ok(()) }
                let frame: ScreenFrame = latest.borrow_and_update().clone().context("No captured screen frame")?;
                tokio::select! {
                    result = timeout(Duration::from_secs(1), async {
                        socket.write_u32(frame.jpeg.len() as u32).await?;
                        socket.write_all(&frame.jpeg).await?; socket.flush().await
                    }) => result.context("Screen receiver is too slow; sharing stopped")??,
                    _ = cancel.changed() => return Ok(()),
                }
                delivered.store(true, Ordering::Relaxed);
                if !started { self.screen_started(op, device, "desktop").await; started = true; }
                tokio::select! {
                    result = latest.changed() => result.context("Desktop screen capture stopped")?,
                    _ = tokio::time::sleep(Duration::from_millis(200)) => {},
                    _ = cancel.changed() => return Ok(()),
                }
            }
        }.await;
        reader.abort();
        reader.await.ok();
        controller.abort();
        controller.await.ok();
        stop_capture(&mut child).await;
        status.abort();
        status.await.ok();
        self.recover_display().await;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn rejects_large_or_invalid_frames() {
        assert!(read_jpeg(&mut &262145u32.to_be_bytes()[..]).await.is_err());
        assert!(read_jpeg(&mut &[0, 0, 0, 4, 1, 2, 3, 4][..]).await.is_err());
    }
}
