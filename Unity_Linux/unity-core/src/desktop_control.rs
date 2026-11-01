use super::Engine;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::time::timeout;
use unity_protocol::{Capability, Message};

pub(super) fn capabilities(disabled: &[String]) -> Vec<Capability> {
    [
        "lock",
        "screen",
        "desktop_media",
        "sound",
        "audio",
        "media_artwork",
    ]
    .iter()
    .map(|id| {
        let mut cap = Capability::available(id);
        if disabled.iter().any(|f| {
            f == id || matches!(*id, "desktop_media" | "sound" | "media_artwork") && f == "media"
        }) {
            cap.status = unity_protocol::Availability::Disabled;
            cap.reason = "Disabled on the computer".into();
        }
        cap
    })
    .collect()
}
pub(super) async fn lock_desktop() -> Result<()> {
    let bus = zbus::Connection::session().await?;
    for (service, path) in [
        ("org.freedesktop.ScreenSaver", "/ScreenSaver"),
        ("org.gnome.ScreenSaver", "/org/gnome/ScreenSaver"),
    ] {
        let proxy = zbus::Proxy::new(&bus, service, path, service).await?;
        if matches!(
            timeout(Duration::from_secs(3), proxy.call::<_, _, ()>("Lock", &())).await,
            Ok(Ok(()))
        ) {
            return Ok(());
        }
    }
    let mut command = tokio::process::Command::new("loginctl");
    command.arg("lock-session").kill_on_drop(true);
    if let Ok(session) = std::env::var("XDG_SESSION_ID") {
        command.arg(session);
    }
    let output = timeout(Duration::from_secs(3), command.output()).await??;
    if !output.status.success() {
        bail!(
            "Desktop lock service unavailable: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )
    }
    Ok(())
}
impl Engine {
    pub(super) async fn phone_request(
        self: std::sync::Arc<Self>,
        device: String,
        generation: String,
        id: String,
        feature: String,
        action: String,
        data: Value,
    ) {
        let result: Result<Value> = async {
            if !unity_protocol::valid_id(&id)
                || !matches!(
                    feature.as_str(),
                    "lock" | "screen" | "desktop_media" | "sound" | "audio"
                )
            {
                bail!("Unsupported desktop request")
            }
            unity_plugins::plugin(&feature)
                .context("unknown feature")?
                .command(&action, &data)?;
            {
                let state = self.state.lock().await;
                let session = state.sessions.get(&device).context("device disconnected")?;
                let d = state.devices.get(&device).context("unknown device")?;
                if session.generation(&generation).is_none()
                    || d.disabled.contains(&feature)
                    || matches!(feature.as_str(), "desktop_media" | "sound")
                        && d.disabled.iter().any(|f| f == "media")
                    || !d
                        .capabilities
                        .iter()
                        .any(|c| c.id == feature && unity_plugins::command_available(c))
                {
                    bail!("Desktop feature disabled or not authorized for this session")
                }
            }
            if feature == "sound" {
                if data["source"] != "desktop" {
                    bail!("Phone requests can only control laptop output")
                }
                self.sound
                    .control(
                        &self,
                        &device,
                        &generation,
                        &action,
                        &data,
                        &format!("{device}:{generation}:{id}"),
                    )
                    .await
            } else if feature == "desktop_media" {
                self.desktop_media
                    .control(
                        &self,
                        &device,
                        &generation,
                        &action,
                        &data,
                        &format!("{device}:{generation}:{id}"),
                    )
                    .await
            } else if feature == "lock" {
                lock_desktop().await?;
                Ok(json!({"state":"locked"}))
            } else if matches!(action.as_str(), "input" | "display") {
                self.screen_control(&device, &action, data).await
            } else {
                let operation = self
                    .execute(&device, &feature, &action, data, &format!("phone:{device}"))
                    .await?;
                Ok(json!({"state":"requested","operation":operation}))
            }
        }
        .await;
        let message = match result {
            Ok(data) => Message::Result {
                id,
                ok: true,
                data,
                error: String::new(),
            },
            Err(error) => Message::Result {
                id,
                ok: false,
                data: Value::Null,
                error: error.to_string().chars().take(512).collect(),
            },
        };
        let state = self.state.lock().await;
        if let Some(session) = state
            .sessions
            .get(&device)
            .and_then(|links| links.generation(&generation))
        {
            session.tx.try_send(message).ok();
        }
    }
}
