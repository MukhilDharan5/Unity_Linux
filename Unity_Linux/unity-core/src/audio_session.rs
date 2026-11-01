//! A separate, session-bound TLS stream keeps transfers out of the audio path.
use super::{audio, Engine, Session, State};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{mpsc, oneshot, watch},
    time::timeout,
};
use unity_protocol::Message;
use unity_transport::SecureStream;

pub(super) struct AudioSession {
    device: String,
    generation: String,
    token: String,
    socket: Option<oneshot::Sender<SecureStream>>,
    pub stop: watch::Sender<bool>,
    done: watch::Receiver<Option<String>>,
    phone_error: watch::Sender<Option<String>>,
    pub operation: String,
}
pub(super) enum Work {
    Start {
        device: String,
        token: String,
        source: String,
        transport: String,
        format: audio::Format,
        socket: oneshot::Receiver<SecureStream>,
        cancel: watch::Receiver<bool>,
        done: watch::Sender<Option<String>>,
        phone_error: watch::Receiver<Option<String>>,
    },
    Stop {
        done: Option<watch::Receiver<Option<String>>>,
    },
}
pub(super) fn cancel_device(state: &State, device: &str) {
    if let Some(audio) = state.audio.as_ref().filter(|audio| audio.device == device) {
        audio.stop.send(true).ok();
    }
}
pub(super) fn cancel_generation(state: &mut State, device: &str, generation: &str) {
    if let Some(audio) = state
        .audio
        .as_ref()
        .filter(|s| s.device == device && s.generation == generation)
    {
        audio.stop.send(true).ok();
        if let Some(device) = state.devices.get_mut(device) {
            device.state.remove("audio");
        }
    }
}
pub(super) fn select_session<'a>(
    state: &'a State,
    device: &str,
    data: &Value,
) -> Result<&'a Session> {
    let links = state.sessions.get(device).context("device disconnected")?;
    let kind = match data["transport"].as_str().unwrap_or("auto") {
        "lan" => Some(unity_transport::Kind::Lan),
        "bluetooth" => Some(unity_transport::Kind::Bluetooth),
        _ => None,
    };
    if let Some(kind) = kind {
        links
            .0
            .iter()
            .find(|s| s.kind == kind && !*s.stop.borrow())
            .context(
                "Selected audio connection is unavailable; connect it or choose another transport",
            )
    } else {
        links
            .select(unity_transport::Workload::Bulk)
            .context("device disconnected")
    }
}
pub(super) fn prepare(
    state: &mut State,
    device: &str,
    action: &str,
    data: &Value,
    operation: &str,
) -> Result<Work> {
    if action == "stop" {
        let done = state
            .audio
            .as_ref()
            .filter(|audio| audio.device == device)
            .map(|audio| {
                audio.stop.send(true).ok();
                audio.done.clone()
            });
        return Ok(Work::Stop { done });
    }
    if state.audio.is_some() {
        bail!("Laptop audio is already being routed; stop it before selecting another phone")
    }
    let session = select_session(state, device, data)?;
    let generation = session.generation.clone();
    let transport = if session.kind == unity_transport::Kind::Bluetooth {
        "bluetooth"
    } else {
        "lan"
    }
    .to_string();
    let format = audio::Format::for_bluetooth(session.kind == unity_transport::Kind::Bluetooth);
    let source = data["source"].as_str().unwrap_or("desktop").to_string();
    let token = hex::encode(unity_security::random_bytes());
    let (tx, socket) = oneshot::channel();
    let (stop, cancel) = watch::channel(false);
    let (done, finished) = watch::channel(None);
    let (phone_failed, phone_error) = watch::channel(None);
    state.audio = Some(AudioSession {
        device: device.into(),
        generation,
        token: token.clone(),
        socket: Some(tx),
        stop,
        done: finished,
        phone_error: phone_failed,
        operation: operation.into(),
    });
    state
        .devices
        .get_mut(device)
        .context("unknown device")?
        .state
        .insert(
            "audio".into(),
            json!({"state":"starting","operation":operation,"source":source,"transport":transport}),
        );
    Ok(Work::Start {
        device: device.into(),
        token,
        source,
        transport,
        format,
        socket,
        cancel,
        done,
        phone_error,
    })
}
async fn wait_done(done: &mut watch::Receiver<Option<String>>) -> Result<()> {
    if done.borrow().is_none() {
        timeout(Duration::from_secs(10), done.changed()).await??;
    }
    if let Some(error) = done.borrow().as_ref().filter(|error| !error.is_empty()) {
        bail!("{error}")
    }
    Ok(())
}
impl Engine {
    pub(super) async fn phone_audio_failed(
        &self,
        device: &str,
        generation: &str,
        operation: &str,
        error: &str,
    ) -> bool {
        let mut state = self.state.lock().await;
        let error = if error.is_empty() {
            "Phone audio playback stopped"
        } else {
            error
        };
        if let Some(audio) = state.audio.as_ref().filter(|audio| {
            audio.device == device && audio.generation == generation && audio.operation == operation
        }) {
            audio.phone_error.send(Some(error.into())).ok();
            audio.stop.send(true).ok();
            return true;
        }
        // A socket close can arrive before the control-link error. Preserve
        // that useful error after cleanup, without touching a newer stream.
        let matches = state.operations.get(operation).is_some_and(|op| {
            op.device == device && op.generation == generation && op.feature == "audio"
        });
        if matches {
            if let Some(audio) = state
                .devices
                .get_mut(device)
                .and_then(|d| d.state.get_mut("audio"))
            {
                if audio["operation"] == operation && audio["state"] == "failed" {
                    audio["error"] = json!(error);
                    drop(state);
                    self.changed.send(device.into()).ok();
                    return true;
                }
            }
        }
        false
    }
    pub async fn shutdown_audio(&self) {
        let done = {
            let state = self.state.lock().await;
            state.audio.as_ref().map(|audio| {
                audio.stop.send(true).ok();
                audio.done.clone()
            })
        };
        if let Some(mut done) = done {
            wait_done(&mut done).await.ok();
        }
    }
    pub(super) async fn accept_audio(
        &self,
        version: u32,
        device: &str,
        token: &str,
        kind: unity_transport::Kind,
        socket: SecureStream,
    ) -> Result<()> {
        if version != 1 || !unity_protocol::valid_id(device) || token.len() != 64 {
            bail!("invalid audio identity")
        }
        let mut state = self.state.lock().await;
        let audio = state
            .audio
            .as_ref()
            .context("Audio routing was not requested")?;
        let equal = token
            .bytes()
            .zip(audio.token.bytes())
            .fold(0u8, |diff, (a, b)| diff | (a ^ b))
            == 0;
        if audio.device != device
            || !equal
            || *audio.stop.borrow()
            || state.sessions.get(device).is_none_or(|links| {
                links
                    .generation(&audio.generation)
                    .is_none_or(|link| link.kind != kind)
            })
            || !state.trust.devices.contains_key(device)
            || state
                .devices
                .get(device)
                .is_none_or(|d| d.disabled.iter().any(|f| f == "audio"))
        {
            bail!("Audio stream is not authorized for this session")
        }
        // A two-packet send buffer cannot sustain 48 kHz PCM when a phone's
        // Wi-Fi power saving delays ACKs. Keep it bounded, with enough room
        // for a short radio sleep; capture still retains only its latest packet.
        let bytes: libc::c_int = 32 * 1024;
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
            .audio
            .as_mut()
            .unwrap()
            .socket
            .take()
            .context("Audio token already used")?
            .send(socket)
            .map_err(|_| anyhow::anyhow!("Audio request expired"))?;
        Ok(())
    }
    pub(super) async fn run_audio_operation(
        &self,
        op: &str,
        tx: &mpsc::Sender<Message>,
        work: Work,
    ) {
        match work {
            Work::Stop { mut done } => {
                let result = if let Some(done) = &mut done {
                    wait_done(done).await
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
                token,
                source,
                transport,
                format,
                socket,
                mut cancel,
                done,
                phone_error,
            } => {
                self.changed.send(device.clone()).ok();
                let result = async {
                    if *cancel.borrow() { return Ok(()) }
                    timeout(Duration::from_secs(3), tx.send(Message::Command {
                        id: op.into(), feature: "audio".into(), action: "start".into(),
                        data: json!({"token":token,"request_id":op,"rate":format.rate,"channels":format.channels,
                            "frames":format.frames,"format":"s16le","source":source,"transport":transport}),
                    })).await??;
                    let mut socket = tokio::select! {
                        result = timeout(Duration::from_secs(if source == "phone" { 120 } else { 15 }), socket) => result.context("Phone audio connection timed out")??,
                        _ = cancel.changed() => return Ok(()),
                    };
                    if *cancel.borrow() { return Ok(()) }
                    if source == "phone" {
                        return self.receive_audio(op, &device, &mut socket, format, &mut cancel).await;
                    }
                    let mut capture = audio::Capture::open(&self.dir, format).await?;
                    let streaming = self.stream_audio(op, &device, &mut socket, &mut capture, &mut cancel).await;
                    drop(socket);
                    let restored = capture.restore().await;
                    streaming.and(restored)
                }.await;
                let error = phone_error
                    .borrow()
                    .clone()
                    .unwrap_or_else(|| result.err().map(|e| e.to_string()).unwrap_or_default());
                // Also closes a phone receiver that is still connecting or awaiting Ready.
                tx.try_send(Message::Command {
                    id: uuid::Uuid::new_v4().simple().to_string(),
                    feature: "audio".into(),
                    action: "stop".into(),
                    data: json!({"request_id":op}),
                })
                .ok();
                let mut state = self.state.lock().await;
                if state.audio.as_ref().is_some_and(|a| a.operation == op) {
                    let audio = state.audio.take().unwrap();
                    if state
                        .sessions
                        .get(&device)
                        .is_some_and(|links| links.generation(&audio.generation).is_some())
                    {
                        if let Some(d) = state.devices.get_mut(&device).filter(|d| {
                            !d.disabled.iter().any(|f| f == "audio")
                                && d.capabilities.iter().any(|c| {
                                    c.id == "audio"
                                        && c.status == unity_protocol::Availability::Available
                                })
                        }) {
                            d.state.insert("audio".into(), json!({"state":if error.is_empty() {"stopped"} else {"failed"},"error":error,"operation":op,"source":source,"transport":transport}));
                        }
                    }
                }
                let pending = state
                    .operations
                    .get(op)
                    .is_some_and(|o| o.status == "running");
                drop(state);
                if pending {
                    self.finish(
                        op,
                        error.is_empty(),
                        if error.is_empty() {
                            json!({"state":"stopped"})
                        } else {
                            Value::Null
                        },
                        error.clone(),
                    )
                    .await;
                }
                self.changed.send(device).ok();
                done.send(Some(error)).ok();
            }
        }
    }
    async fn receive_audio(
        &self,
        op: &str,
        device: &str,
        socket: &mut SecureStream,
        format: audio::Format,
        cancel: &mut watch::Receiver<bool>,
    ) -> Result<()> {
        let mut playback = audio::Playback::open(format)?;
        let result = async {
            unity_transport::write_frame(socket, &Message::Ready).await?;
            let mut previous = None;
            loop {
                if *cancel.borrow() {
                    return Ok(());
                }
                let mut packet = vec![0; 8 + format.pcm_bytes()];
                tokio::select! {
                    _ = cancel.changed() => return Ok(()),
                    result = timeout(Duration::from_secs(5), socket.read_exact(&mut packet)) => {
                        result.context("Phone audio capture stopped")??;
                    }
                }
                let sequence = u64::from_be_bytes(packet[..8].try_into().unwrap());
                if sequence > i64::MAX as u64 || previous.is_some_and(|p| sequence <= p) {
                    bail!("Invalid phone audio packet sequence")
                }
                tokio::select! {
                    _ = cancel.changed() => return Ok(()),
                    result = playback.write(&packet[8..]) => result?,
                }
                if previous.is_none() {
                    self.finish(op, true, json!({"state":"streaming"}), String::new())
                        .await;
                    if let Some(audio) = self
                        .state
                        .lock()
                        .await
                        .devices
                        .get_mut(device)
                        .and_then(|d| d.state.get_mut("audio"))
                        .filter(|a| a["operation"] == op)
                    {
                        audio["state"] = json!("streaming");
                    }
                    self.changed.send(device.into()).ok();
                }
                previous = Some(sequence);
            }
        }
        .await;
        playback.close().await;
        result
    }
    async fn stream_audio(
        &self,
        op: &str,
        device: &str,
        socket: &mut SecureStream,
        capture: &mut audio::Capture,
        cancel: &mut watch::Receiver<bool>,
    ) -> Result<()> {
        let (mut packets, reader) = capture.packets()?;
        let result = async {
            if *cancel.borrow() { return Ok(()) }
            timeout(Duration::from_secs(3), packets.changed()).await??;
            // A real capture packet must exist before acknowledging the route.
            if packets.borrow().is_none() { bail!("Laptop audio capture failed") }
            timeout(Duration::from_secs(3), unity_transport::write_frame(socket, &Message::Ready)).await??;
            self.finish(op, true, json!({"state":"streaming"}), String::new()).await;
            if let Some(d) = self.state.lock().await.devices.get_mut(device) {
                if let Some(audio) = d.state.get_mut("audio").filter(|a| a["operation"] == op) { audio["state"] = json!("streaming"); }
            }
            self.changed.send(device.into()).ok();
            loop {
                if *cancel.borrow() { return Ok(()) }
                let packet = packets.borrow_and_update().clone().context("missing PCM packet")?;
                tokio::select! {
                    _ = cancel.changed() => return Ok(()),
                    result = timeout(Duration::from_secs(1), async {
                        socket.write_all(&packet).await?;
                        socket.flush().await
                    }) => result.context("Audio network stalled; routing stopped to avoid delayed playback")??,
                }
                tokio::select! {
                    _ = cancel.changed() => return Ok(()),
                    result = timeout(Duration::from_secs(3), packets.changed()) => result.context("Laptop audio capture stopped")??,
                }
            }
        }.await;
        reader.abort();
        reader.await.ok();
        result
    }
}
