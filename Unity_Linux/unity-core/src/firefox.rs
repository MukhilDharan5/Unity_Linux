//! Local Firefox native-messaging receiver. Firefox retains the full checkpoints;
//! only bounded page summaries are held here, in memory, while a profile connects.
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    os::unix::fs::{FileTypeExt, MetadataExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{UnixListener, UnixStream},
    sync::{broadcast, mpsc, Mutex, Semaphore},
    time::timeout,
};

const MAX_FRAME: usize = 512 * 1024;
struct Profile {
    session: String,
    tx: mpsc::Sender<Value>,
    summary: Value,
    result: Value,
}
struct State {
    enabled: bool,
    profiles: BTreeMap<String, Profile>,
    error: String,
}
pub(crate) struct Backend {
    dir: PathBuf,
    state: Mutex<State>,
    changed: broadcast::Sender<String>,
}
impl Backend {
    pub fn new(dir: &Path, changed: broadcast::Sender<String>) -> Result<Arc<Self>> {
        let config = dir.join("firefox.json");
        let enabled = if config.exists() {
            let value: Value = serde_json::from_slice(&std::fs::read(config)?)?;
            value["enabled"]
                .as_bool()
                .context("invalid Firefox configuration")?
        } else {
            false
        };
        Ok(Arc::new(Self {
            dir: dir.into(),
            state: Mutex::new(State {
                enabled,
                profiles: BTreeMap::new(),
                error: String::new(),
            }),
            changed,
        }))
    }
    fn notify(&self) {
        self.changed.send(String::new()).ok();
    }
    pub async fn status(&self) -> Value {
        let state = self.state.lock().await;
        json!({"enabled":state.enabled,"error":state.error,"profiles":state.profiles.iter().map(|(id,p)|json!({"id":id,"summary":p.summary,"last_result":p.result})).collect::<Vec<_>>()})
    }
    pub async fn set_enabled(&self, enabled: bool) -> Result<()> {
        let mut state = self.state.lock().await;
        if enabled && !state.error.is_empty() {
            bail!("Firefox receiver unavailable: {}", state.error);
        }
        unity_security::write_private(
            &self.dir.join("firefox.json"),
            &serde_json::to_vec(&json!({"version":1,"enabled":enabled}))?,
        )?;
        state.enabled = enabled;
        for profile in state.profiles.values_mut() {
            profile.summary = json!({});
            profile.result = Value::Null;
            // The extension resends current summaries only after an enabled hello.
            profile
                .tx
                .try_send(json!({"type":"hello","version":1,"enabled":enabled}))
                .ok();
        }
        drop(state);
        self.notify();
        Ok(())
    }
    pub async fn restore(&self, profile_id: &str, context_id: &str) -> Result<String> {
        let mut state = self.state.lock().await;
        if !state.enabled {
            bail!("Firefox extension support is disabled");
        }
        let profile = state
            .profiles
            .get_mut(profile_id)
            .context("Firefox profile disconnected")?;
        if !context_id.is_empty()
            && !profile.summary["contexts"].as_array().is_some_and(|items| {
                items
                    .iter()
                    .any(|c| c["id"] == context_id && c["closed"] == false)
            })
        {
            bail!("unknown or closed Firefox context");
        }
        let id = uuid::Uuid::new_v4().to_string();
        profile
            .tx
            .try_send(json!({"type":"restore","commandId":id,"contextId":context_id}))
            .context("Firefox command queue is full or disconnected")?;
        profile.result = json!({"commandId":id,"ok":true,"note":"Waiting for Firefox"});
        drop(state);
        self.notify();
        Ok(id)
    }
    pub async fn start(self: &Arc<Self>) {
        let result = self.listener().await;
        match result {
            Ok(listener) => {
                let backend = self.clone();
                tokio::spawn(async move {
                    let limit = Arc::new(Semaphore::new(4));
                    loop {
                        let Ok((stream, _)) = listener.accept().await else {
                            break;
                        };
                        let Ok(permit) = limit.clone().try_acquire_owned() else {
                            continue;
                        };
                        if stream
                            .peer_cred()
                            .ok()
                            .is_none_or(|c| c.uid() != unsafe { libc::geteuid() })
                        {
                            continue;
                        }
                        let backend = backend.clone();
                        tokio::spawn(async move {
                            let _permit = permit;
                            if let Err(error) = backend.connection(stream).await {
                                tracing::debug!(%error,"Firefox connection ended");
                            }
                        });
                    }
                });
            }
            Err(error) => {
                self.state.lock().await.error = error.to_string();
                tracing::warn!(%error, "Firefox receiver unavailable");
            }
        }
    }
    async fn listener(&self) -> Result<UnixListener> {
        let path = self.dir.join("firefox.sock");
        match std::fs::symlink_metadata(&path) {
            Ok(meta) => {
                if !meta.file_type().is_socket() || meta.uid() != unsafe { libc::geteuid() } {
                    bail!("Firefox socket path is not an owned socket");
                }
                match UnixStream::connect(&path).await {
                    Ok(_) => bail!("Firefox receiver already running"),
                    Err(e) if e.kind() == std::io::ErrorKind::ConnectionRefused => {
                        std::fs::remove_file(&path)?
                    }
                    Err(e) => return Err(e.into()),
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
        let listener = UnixListener::bind(&path)?;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        Ok(listener)
    }
    async fn connection(&self, mut stream: UnixStream) -> Result<()> {
        let hello = timeout(Duration::from_secs(10), read_frame(&mut stream)).await??;
        let profile_id = hello["profileId"]
            .as_str()
            .filter(|id| valid_id(id))
            .context("invalid Firefox profile ID")?
            .to_owned();
        if hello["type"] != "hello" || hello["version"] != 1 {
            bail!("unsupported Firefox bridge version");
        }
        let session = uuid::Uuid::new_v4().to_string();
        let (tx, mut rx) = mpsc::channel(8);
        {
            let mut state = self.state.lock().await;
            // A second live connection must not take over another profile.
            if state.profiles.contains_key(&profile_id) {
                bail!("Firefox profile already connected");
            }
            write_frame(
                &mut stream,
                &json!({"type":"hello","version":1,"enabled":state.enabled}),
            )
            .await?;
            state.profiles.insert(
                profile_id.clone(),
                Profile {
                    session: session.clone(),
                    tx: tx.clone(),
                    summary: json!({}),
                    result: Value::Null,
                },
            );
        }
        self.notify();
        let (mut reader, mut writer) = stream.into_split();
        let writer_task = tokio::spawn(async move {
            while let Some(value) = rx.recv().await {
                if !matches!(
                    timeout(Duration::from_secs(5), write_frame(&mut writer, &value)).await,
                    Ok(Ok(()))
                ) {
                    break;
                }
            }
        });
        let result = async {
            loop {
                let message = timeout(Duration::from_secs(90), read_frame(&mut reader)).await??;
                let mut state = self.state.lock().await;
                let enabled = state.enabled;
                let profile = state.profiles.get_mut(&profile_id).context("Firefox session ended")?;
                match message["type"].as_str() {
                    Some("sync") if enabled => {
                        let summary = &message["summary"];
                        validate_summary(summary)?;
                        profile.summary = summary.clone();
                        tx.try_send(json!({"type":"synced","sequence":message["sequence"]})).context("Firefox reply queue full")?;
                    }
                    Some("sync") => { tx.try_send(json!({"type":"hello","version":1,"enabled":false})).ok(); }
                    Some("result") if enabled => {
                        if message["commandId"] == profile.result["commandId"] && message["ok"].is_boolean() {
                            let note = message["note"].as_str().filter(|s|s.len() <= 512).unwrap_or("Firefox replied");
                            profile.result = json!({"commandId":message["commandId"],"ok":message["ok"],"note":note});
                        }
                    }
                    Some("ping") => { tx.try_send(json!({"type":"pong"})).context("Firefox reply queue full")?; }
                    _ => bail!("invalid Firefox bridge message"),
                }
                drop(state); self.notify();
            }
        }.await;
        writer_task.abort();
        let mut state = self.state.lock().await;
        if state
            .profiles
            .get(&profile_id)
            .is_some_and(|p| p.session == session)
        {
            state.profiles.remove(&profile_id);
        }
        drop(state);
        self.notify();
        result
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 160
        && id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b":_-".contains(&c))
}
fn validate_summary(value: &Value) -> Result<()> {
    let contexts = value["contexts"]
        .as_array()
        .context("missing Firefox contexts")?;
    if contexts.len() > 100 || !value["tracking"].is_boolean() {
        bail!("invalid Firefox summary");
    }
    let mut ids = std::collections::BTreeSet::new();
    for c in contexts {
        let id = c["id"]
            .as_str()
            .filter(|s| valid_id(s))
            .context("invalid Firefox context ID")?;
        let url = c["url"].as_str().context("missing Firefox URL")?;
        if !ids.insert(id)
            || url.len() > 4096
            || !(url.starts_with("https://") || url.starts_with("http://"))
            || url.chars().any(char::is_control)
            || !c["closed"].is_boolean()
            || c["updatedAt"].as_u64().is_none()
            || c["formCount"].as_u64().is_none_or(|n| n > 40)
            || !(c["scrollProgress"].is_null()
                || c["scrollProgress"]
                    .as_f64()
                    .is_some_and(|n| (0.0..=1.0).contains(&n)))
        {
            bail!("invalid Firefox page summary");
        }
        let media = c["media"]
            .as_array()
            .context("invalid Firefox media summary")?;
        if media.len() > 16
            || media.iter().any(|m| {
                !matches!(m["kind"].as_str(), Some("audio" | "video"))
                    || !m["time"]
                        .as_f64()
                        .is_some_and(|n| (0.0..=8_640_000.0).contains(&n))
            })
        {
            bail!("invalid Firefox media position");
        }
        // Reject extra fields: full form values and checkpoint data never cross this boundary.
        if c.as_object().is_none_or(|o| {
            o.keys().any(|k| {
                !matches!(
                    k.as_str(),
                    "id" | "url"
                        | "closed"
                        | "updatedAt"
                        | "formCount"
                        | "scrollProgress"
                        | "media"
                )
            })
        }) {
            bail!("unexpected Firefox context field");
        }
        if media.iter().any(|m| {
            m.as_object()
                .is_none_or(|o| o.keys().any(|k| !matches!(k.as_str(), "kind" | "time")))
        }) {
            bail!("unexpected Firefox media field");
        }
    }
    if value["activeId"]
        .as_str()
        .is_some_and(|id| !ids.contains(id))
        || !(value["activeId"].is_null() || value["activeId"].is_string())
    {
        bail!("invalid Firefox active context");
    }
    if value.as_object().is_none_or(|o| {
        o.keys().any(|k| {
            !matches!(
                k.as_str(),
                "contexts" | "activeId" | "tracking" | "restoreState"
            )
        })
    }) || value["restoreState"].as_str().is_none_or(|s| s.len() > 40)
    {
        bail!("invalid Firefox status");
    }
    Ok(())
}
async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Value> {
    let mut header = [0; 4];
    reader.read_exact(&mut header).await?;
    let size = u32::from_ne_bytes(header) as usize;
    if size == 0 || size > MAX_FRAME {
        bail!("invalid Firefox frame size");
    }
    let mut data = vec![0; size];
    reader.read_exact(&mut data).await?;
    Ok(serde_json::from_slice(&data)?)
}
async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, value: &Value) -> Result<()> {
    let data = serde_json::to_vec(value)?;
    if data.len() > MAX_FRAME {
        bail!("Firefox response too large");
    }
    writer.write_all(&(data.len() as u32).to_ne_bytes()).await?;
    writer.write_all(&data).await?;
    Ok(())
}

impl crate::Engine {
    pub async fn start_firefox(&self) {
        self.firefox.start().await;
    }
    pub async fn firefox_status(&self) -> Value {
        self.firefox.status().await
    }
    pub async fn set_firefox_enabled(&self, enabled: bool) -> Result<()> {
        self.firefox.set_enabled(enabled).await
    }
    pub async fn restore_firefox_context(&self, profile: &str, context: &str) -> Result<String> {
        self.firefox.restore(profile, context).await
    }
}
