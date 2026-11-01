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
use zbus::zvariant::{Fd, OwnedObjectPath, OwnedValue, Value as Variant};

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
            "clipboard" if value["automatic"] != true => {
                value["text"].as_str().map(|text| Receipt {
                    title: "Text received".into(),
                    body: format!("{device} · {}", text.chars().take(240).collect::<String>()),
                    target: Target::Text(text.into()),
                })
            }
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
    closed: Option<Instant>,
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
                    active.insert(id, Active { target: receipt.target, created: Instant::now(), activation: String::new(), closed: None });
                }
            },
            event = signals.next() => {
                let Some(event) = event else { return Ok(()) };
                let owner = dbus.get_name_owner(SERVICE.try_into()?).await.ok().map(|owner| owner.to_string());
                if owner != server_owner { active.clear(); server_owner = owner; }
                if event.header().sender().map(|sender| sender.as_str()) != server_owner.as_deref() { continue; }
                match event.header().member().map(|member| member.as_str()) {
                    Some("ActionInvoked") => {
                        let (id, action): (u32, String) = event.body().deserialize()?;
                        if matches!(action.as_str(), "default" | "open") {
                            if let Some(item) = active.remove(&id) {
                                // Some desktops close a popup immediately before emitting its action.
                                if item.closed.is_some_and(|at| at.elapsed() > Duration::from_secs(5)) { continue; }
                                let bus = bus.clone(); let directory = directory.clone();
                                tokio::spawn(async move {
                                    if let Err(error) = open(&bus, &directory, item.target, &item.activation).await {
                                        tracing::warn!(%error, "Could not open received item");
                                        if let Ok(notifications) = zbus::Proxy::new(&bus, SERVICE, PATH, SERVICE).await {
                                            let hints = HashMap::from([("desktop-entry", Variant::from("org.unityconnect.Unity"))]);
                                            timeout(Duration::from_secs(3), notifications.call::<_, _, u32>("Notify", &("Unity", 0u32, "org.unityconnect.Unity", "Could not open received item", "The item is still saved. Install an app that supports this file type, then open it from Unity or Downloads.", Vec::<&str>::new(), hints, 10000i32))).await.ok();
                                        }
                                    }
                                });
                                timeout(Duration::from_secs(1), proxy.call::<_, _, ()>("CloseNotification", &(id,))).await.ok();
                            }
                        }
                    }
                    Some("NotificationClosed") => {
                        let (id, _reason): (u32, u32) = event.body().deserialize()?;
                        if let Some(item) = active.get_mut(&id) { item.closed = Some(Instant::now()); }
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
    let (file, fallback) = match target {
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
            (
                Some(file),
                path.to_str()
                    .context("Invalid received filename")?
                    .to_owned(),
            )
        }
        Target::Link(url) => {
            unity_plugins::plugin("urls")
                .unwrap()
                .event(&serde_json::json!({"url":url}))?;
            (None, url)
        }
        Target::Text(_) => unreachable!(),
    };
    match portal_open(bus, file.as_ref(), &fallback, activation).await {
        Ok(()) => return Ok(()),
        Err(error) => tracing::debug!(%error, "Received-item portal failed; trying Gio"),
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

async fn portal_open(
    bus: &zbus::Connection,
    file: Option<&std::fs::File>,
    target: &str,
    activation: &str,
) -> Result<()> {
    // Subscribe before calling: a portal can emit Response before returning the request path.
    let rule = zbus::MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .sender("org.freedesktop.portal.Desktop")?
        .interface("org.freedesktop.portal.Request")?
        .member("Response")?
        .build();
    let mut responses = zbus::MessageStream::for_match_rule(rule, bus, Some(32)).await?;
    let token = format!("unity_received_{}", uuid::Uuid::new_v4().simple());
    let mut options = HashMap::from([
        ("writable", Variant::from(false)),
        ("ask", Variant::from(false)),
        ("handle_token", Variant::from(token.as_str())),
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
    let reply = timeout(Duration::from_secs(5), async {
        match file {
            Some(file) => {
                portal
                    .call_method("OpenFile", &("", Fd::from(file), options))
                    .await
            }
            None => portal.call_method("OpenURI", &("", target, options)).await,
        }
    })
    .await??;
    let request: OwnedObjectPath = reply.body().deserialize()?;
    let owner = reply
        .header()
        .sender()
        .context("Portal reply has no sender")?
        .to_owned();
    let result = timeout(Duration::from_secs(60), async {
        while let Some(response) = responses.next().await {
            let response = response?;
            let header = response.header();
            if header.path() != Some(&request.as_ref()) || header.sender() != Some(&owner) {
                continue;
            }
            let (code, _results): (u32, HashMap<String, OwnedValue>) =
                response.body().deserialize()?;
            // Cancellation is a completed user choice, never a reason to force another opener.
            return if code <= 1 {
                Ok(())
            } else {
                bail!("Portal could not open received item ({code})")
            };
        }
        bail!("Portal response stream closed")
    })
    .await;
    match result {
        Ok(result) => result,
        Err(error) => {
            if let Ok(request) = zbus::Proxy::new(
                bus,
                owner.as_str(),
                request.as_str(),
                "org.freedesktop.portal.Request",
            )
            .await
            {
                timeout(
                    Duration::from_secs(1),
                    request.call::<_, _, ()>("Close", &()),
                )
                .await
                .ok();
            }
            Err(error.into())
        }
    }
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
