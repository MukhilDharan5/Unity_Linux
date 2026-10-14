//! A separate, session-bound TLS stream keeps transfers out of the audio path.
use super::{audio, Engine, State};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::{
    io::AsyncWriteExt,
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
    pub operation: String,
}
pub(super) enum Work {
    Start {
        device: String,
        token: String,
        socket: oneshot::Receiver<SecureStream>,
        cancel: watch::Receiver<bool>,
        done: watch::Sender<Option<String>>,
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
pub(super) fn prepare(
    state: &mut State,
    device: &str,
    action: &str,
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
    let generation = state
        .sessions
        .get(device)
        .and_then(|links| links.select(unity_transport::Workload::Bulk))
        .context("device disconnected")?
        .generation
        .clone();
    let token = hex::encode(unity_security::random_bytes());
    let (tx, socket) = oneshot::channel();
    let (stop, cancel) = watch::channel(false);
    let (done, finished) = watch::channel(None);
    state.audio = Some(AudioSession {
        device: device.into(),
        generation,
        token: token.clone(),
        socket: Some(tx),
        stop,
        done: finished,
        operation: operation.into(),
    });
    state
        .devices
        .get_mut(device)
        .context("unknown device")?
        .state
        .insert("audio".into(), json!({"state":"starting"}));
    Ok(Work::Start {
        device: device.into(),
        token,
        socket,
        cancel,
        done,
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
        // Bound queued audio on both TCP and RFCOMM sockets.
        let bytes: libc::c_int = 4096;
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
                socket,
                mut cancel,
                done,
            } => {
                self.changed.send(device.clone()).ok();
                let result = async {
                    if *cancel.borrow() { return Ok(()) }
                    timeout(Duration::from_secs(3), tx.send(Message::Command {
                        id: op.into(), feature: "audio".into(), action: "start".into(),
                        data: json!({"token":token,"request_id":op,"rate":audio::RATE,"channels":audio::CHANNELS,
                            "frames":audio::FRAMES,"format":"s16le"}),
                    })).await??;
                    let mut socket = tokio::select! {
                        result = timeout(Duration::from_secs(10), socket) => result.context("Phone audio connection timed out")??,
                        _ = cancel.changed() => return Ok(()),
                    };
                    if *cancel.borrow() { return Ok(()) }
                    let mut capture = audio::Capture::open(&self.dir).await?;
                    let streaming = self.stream_audio(op, &device, &mut socket, &mut capture, &mut cancel).await;
                    drop(socket);
                    let restored = capture.restore().await;
                    streaming.and(restored)
                }.await;
                let error = result.err().map(|e| e.to_string()).unwrap_or_default();
                // Also closes a phone receiver that is still connecting or awaiting Ready.
                tx.try_send(Message::Command {
                    id: uuid::Uuid::new_v4().simple().to_string(),
                    feature: "audio".into(),
                    action: "stop".into(),
                    data: json!({}),
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
                            d.state.insert("audio".into(), json!({"state":if error.is_empty() {"stopped"} else {"failed"},"error":error}));
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
                        false,
                        Value::Null,
                        if error.is_empty() {
                            "Audio routing stopped before playback".into()
                        } else {
                            error.clone()
                        },
                    )
                    .await;
                }
                self.changed.send(device).ok();
                done.send(Some(error)).ok();
            }
        }
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
                d.state.insert("audio".into(), json!({"state":"streaming"}));
            }
            self.changed.send(device.into()).ok();
            loop {
                if *cancel.borrow() { return Ok(()) }
                let packet = packets.borrow_and_update().clone().context("missing PCM packet")?;
                tokio::select! {
                    _ = cancel.changed() => return Ok(()),
                    result = timeout(Duration::from_millis(100), async {
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
