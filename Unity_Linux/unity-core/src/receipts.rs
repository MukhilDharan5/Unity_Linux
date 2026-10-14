//! Native receipt notifications are owned by the daemon, independent of any GUI.
use anyhow::{bail, Context, Result};
use futures_lite::StreamExt;
use serde_json::Value;
use std::{
    collections::HashMap,
    path::PathBuf,
    time::{Duration, Instant},
};
use tokio::{
    process::Command,
    sync::{mpsc, Mutex},
    time::timeout,
};
use zbus::zvariant::{Fd, OwnedObjectPath, Value as Variant};

const SERVICE: &str = "org.freedesktop.Notifications";
const PATH: &str = "/org/freedesktop/Notifications";
#[derive(Clone)]
enum Target {
    File(PathBuf),
    Link(String),
    Text(String),
}
pub(super) struct Receipt {
    title: String,
    body: String,
    target: Target,
}
pub(super) struct Notifications {
    tx: mpsc::Sender<Receipt>,
    rx: Mutex<Option<mpsc::Receiver<Receipt>>>,
    directory: PathBuf,
}
impl Notifications {
    pub fn new(directory: PathBuf) -> Self {
        let (tx, rx) = mpsc::channel(64);
        Self {
            tx,
            rx: Mutex::new(Some(rx)),
            directory,
        }
    }
    pub async fn start(&self) {
        if let Some(rx) = self.rx.lock().await.take() {
            let directory = self.directory.clone();
            tokio::spawn(async move {
                if let Err(error) = run(rx, directory).await {
                    tracing::warn!(%error, "Received-item notifications unavailable");
                }
            });
        }
    }
    pub fn received(&self, device: &str, feature: &str, value: &Value) {
        let receipt = match feature {
            "files" => value["received_path"].as_str().map(|path| Receipt {
                title: "File received".into(),
                body: format!(
                    "{} · {}",
                    device,
                    PathBuf::from(path)
                        .file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                ),
                target: Target::File(path.into()),
            }),
            "urls" => value["url"].as_str().map(|url| Receipt {
                title: "Link received".into(),
                body: format!("{device} · {url}"),
                target: Target::Link(url.into()),
            }),
            "clipboard" => value["text"].as_str().map(|text| Receipt {
                title: "Text received".into(),
                body: format!("{device} · {}", text.chars().take(240).collect::<String>()),
                target: Target::Text(text.into()),
            }),
            _ => None,
        };
        if let Some(receipt) = receipt {
            if self.tx.try_send(receipt).is_err() {
                tracing::warn!("Received-item notification queue full or unavailable");
            }
        }
    }
}
struct Active {
    target: Target,
    created: Instant,
    activation: String,
}
async fn run(mut rx: mpsc::Receiver<Receipt>, directory: PathBuf) -> Result<()> {
    let bus = zbus::Connection::session().await?;
    let proxy = zbus::Proxy::new(&bus, SERVICE, PATH, SERVICE).await?;
    // Keep token, action and close signals in bus order. Separate streams can process
    // an action before the activation token the server sent immediately before it.
    let mut signals = proxy.receive_all_signals().await?;
    let dbus = zbus::fdo::DBusProxy::new(&bus).await?;
    let mut owners = dbus.receive_name_owner_changed().await?;
    let mut active = HashMap::<u32, Active>::new();
    let mut server_owner: Option<String> = None;
    loop {
        tokio::select! {
            receipt = rx.recv() => {
                let Some(receipt) = receipt else { return Ok(()) };
                // Query per receipt: availability and capabilities can change after a server restart.
                let capabilities: Vec<String> = match timeout(Duration::from_secs(3), proxy.call("GetCapabilities", &())).await {
                    Ok(Ok(caps)) => caps,
                    _ => { tracing::debug!("Notification service unavailable; received content remains saved"); continue; }
                };
                let owner = dbus.get_name_owner(SERVICE.try_into()?).await.ok().map(|owner| owner.to_string());
                if server_owner != owner { active.clear(); server_owner = owner; }
                let body = if capabilities.iter().any(|c| c == "body-markup") { escape(&receipt.body) } else { receipt.body.clone() };
                let label = if matches!(receipt.target, Target::Text(_)) { "Open text" } else { "Open" };
                let buttons = if capabilities.iter().any(|c| c == "actions") { vec!["default", label, "open", label] } else { vec![] };
                let hints = HashMap::from([("desktop-entry", Variant::from("org.unityconnect.Unity")), ("category", Variant::from("transfer.complete"))]);
                // Retain the item for a later click; the desktop still controls popup presentation.
                let result = timeout(Duration::from_secs(3), proxy.call::<_, _, u32>("Notify", &("Unity", 0u32, "org.unityconnect.Unity", &receipt.title, body, buttons, hints, 0i32))).await;
                if let Ok(Ok(id)) = result {
                    // Bound retained actions and private text, without affecting the transfer itself.
                    while active.len() >= 128 {
                        if let Some(old) = active.iter().min_by_key(|(_, item)| item.created).map(|(id, _)| *id) {
                            active.remove(&old);
                            timeout(Duration::from_secs(1), proxy.call::<_, _, ()>("CloseNotification", &(old,))).await.ok();
                        }
                    }
                    active.insert(id, Active { target: receipt.target, created: Instant::now(), activation: String::new() });
                }
            },
            event = signals.next() => {
                let Some(event) = event else { return Ok(()) };
                match event.header().member().map(|member| member.as_str()) {
                    Some("ActionInvoked") => {
                        let (id, action): (u32, String) = event.body().deserialize()?;
                        let owner = dbus.get_name_owner(SERVICE.try_into()?).await.ok().map(|owner| owner.to_string());
                        if owner != server_owner { active.clear(); server_owner = owner; }
                        if event.header().sender().map(|sender| sender.as_str()) != server_owner.as_deref() { continue; }
                        if matches!(action.as_str(), "default" | "open") {
                            if let Some(item) = active.remove(&id) {
                                let bus = bus.clone(); let directory = directory.clone();
                                tokio::spawn(async move {
                                    if let Err(error) = open(&bus, &directory, item.target, &item.activation).await { tracing::warn!(%error, "Could not open received item"); }
                                });
                                timeout(Duration::from_secs(1), proxy.call::<_, _, ()>("CloseNotification", &(id,))).await.ok();
                            }
                        }
                    }
                    Some("NotificationClosed") => {
                        let (id, _reason): (u32, u32) = event.body().deserialize()?;
                        active.remove(&id);
                    }
                    Some("ActivationToken") => {
                        let (id, token): (u32, String) = event.body().deserialize()?;
                        if token.len() <= 4096 { if let Some(item) = active.get_mut(&id) { item.activation = token; } }
                    }
                    _ => {},
                }
            },
            event = owners.next() => {
                let Some(event) = event else { return Ok(()) };
                let args = event.args()?;
                if args.name().as_str() == SERVICE {
                    // An earlier owner-change signal may be queued behind a new Notify.
                    // Compare the current owner so it cannot discard the new server's actions.
                    let owner = dbus.get_name_owner(SERVICE.try_into()?).await.ok().map(|owner| owner.to_string());
                    if owner != server_owner { active.clear(); server_owner = owner; }
                }
            }
        }
    }
}
fn escape(text: &str) -> String {
    text.chars()
        .take(1024)
        .collect::<String>()
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}
async fn open(
    bus: &zbus::Connection,
    directory: &std::path::Path,
    target: Target,
    activation: &str,
) -> Result<()> {
    let target = match target {
        Target::Text(text) => {
            let path = directory.join(format!("{}-shared-text.txt", uuid::Uuid::new_v4().simple()));
            unity_security::write_private(&path, text.as_bytes())?;
            Target::File(path)
        }
        target => target,
    };
    let mut options = HashMap::from([
        ("writable", Variant::from(false)),
        ("ask", Variant::from(false)),
    ]);
    if !activation.is_empty() {
        options.insert("activation_token", Variant::from(activation));
    }
    let portal = zbus::Proxy::new(
        bus,
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.OpenURI",
    )
    .await?;
    let (result, fallback) = match target {
        Target::File(path) => {
            use std::os::unix::fs::OpenOptionsExt;
            if path.parent() != Some(directory) {
                bail!("File is outside received storage")
            }
            let file = std::fs::OpenOptions::new()
                .read(true)
                .custom_flags(libc::O_NOFOLLOW)
                .open(&path)?;
            if !file.metadata()?.is_file() || path.parent() != Some(directory) {
                bail!("Received file is missing or outside received storage")
            }
            let fd = Fd::from(&file);
            (
                timeout(
                    Duration::from_secs(5),
                    portal.call::<_, _, OwnedObjectPath>("OpenFile", &("", fd, options)),
                )
                .await,
                path.to_str()
                    .context("Invalid received filename")?
                    .to_owned(),
            )
        }
        Target::Link(url) => {
            unity_plugins::plugin("urls")
                .unwrap()
                .event(&serde_json::json!({"url":url}))?;
            (
                timeout(
                    Duration::from_secs(5),
                    portal.call::<_, _, OwnedObjectPath>("OpenURI", &("", &url, options)),
                )
                .await,
                url,
            )
        }
        Target::Text(_) => unreachable!(),
    };
    if matches!(result, Ok(Ok(_))) {
        return Ok(());
    }
    // Minimal desktop environments can open through Gio without a portal service.
    let mut command = Command::new("gio");
    command.arg("open").arg(&fallback).kill_on_drop(true);
    if !activation.is_empty() {
        command.env("XDG_ACTIVATION_TOKEN", activation);
    }
    let output = timeout(Duration::from_secs(10), command.output()).await??;
    if !output.status.success() {
        bail!("{}", String::from_utf8_lossy(&output.stderr));
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn escapes_received_markup_and_bounds_preview() {
        assert_eq!(
            escape("<img src='secret'>&"),
            "&lt;img src='secret'&gt;&amp;"
        );
        assert_eq!(escape(&"x".repeat(2000)).len(), 1024);
    }
}
