//! Call alerts and actions are daemon-owned; the GTK client may be closed.
use crate::Engine;
use anyhow::Result;
use futures_lite::StreamExt;
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
    time::{Duration, Instant},
};
use zbus::zvariant::Value;
const BUS: &str = "org.freedesktop.Notifications";
struct Alert {
    notification: u32,
    phase: String,
    caller: String,
}
fn caller_key(call: &serde_json::Value) -> String {
    call["number"]
        .as_str()
        .filter(|s| !s.is_empty())
        .or_else(|| call["name"].as_str())
        .unwrap_or("")
        .to_owned()
}
async fn launch_popup(device: &str) {
    launch_client(&["--call-popup", device]).await;
}
pub(super) async fn launch_client(options: &[&str]) {
    // Only use the sibling installed launcher; it verifies and approves its GTK child.
    // Development/test daemons never launch the user's installed UI.
    let Ok(executable) = std::env::current_exe() else {
        return;
    };
    let Some(directory) = executable
        .parent()
        .filter(|p| p.file_name().is_some_and(|n| n == "unity"))
    else {
        return;
    };
    let mut command = tokio::process::Command::new(directory.join("unityctl"));
    if std::env::var_os("DISPLAY").is_none() && std::env::var_os("WAYLAND_DISPLAY").is_none() {
        // The user daemon may have started before Plasma imported its display
        // environment. Read only display-related values from the user manager.
        let environment = tokio::time::timeout(
            Duration::from_secs(2),
            tokio::process::Command::new("systemctl")
                .args(["--user", "show-environment"])
                .output(),
        )
        .await;
        let Ok(Ok(environment)) = environment else {
            return;
        };
        let mut has_display = false;
        for line in String::from_utf8_lossy(&environment.stdout).lines() {
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            if [
                "DISPLAY",
                "WAYLAND_DISPLAY",
                "XDG_RUNTIME_DIR",
                "DBUS_SESSION_BUS_ADDRESS",
                "XDG_SESSION_TYPE",
                "XDG_CURRENT_DESKTOP",
            ]
            .contains(&key)
            {
                command.env(key, value);
                if ["DISPLAY", "WAYLAND_DISPLAY"].contains(&key) && !value.is_empty() {
                    has_display = true;
                }
            }
        }
        if !has_display {
            return;
        }
    }
    match command.arg("launch-gtk").args(options).spawn() {
        Ok(mut child) => {
            tokio::spawn(async move {
                match child.wait().await {
                    Ok(status) if status.success() => (),
                    result => {
                        tracing::warn!(?result, "Call controls launcher stopped unsuccessfully")
                    }
                }
            });
        }
        Err(error) => tracing::warn!(%error, "Could not open call controls"),
    }
}
pub(super) async fn run(
    engine: Arc<Engine>,
    mut stop: tokio::sync::watch::Receiver<bool>,
) -> Result<()> {
    let bus = zbus::Connection::session().await?;
    let proxy = zbus::Proxy::new(&bus, BUS, "/org/freedesktop/Notifications", BUS).await?;
    let mut actions = proxy.receive_signal("ActionInvoked").await?;
    let dbus = zbus::fdo::DBusProxy::new(&bus).await?;
    let mut owners = dbus.receive_name_owner_changed().await?;
    let mut changes = engine.changed.subscribe();
    let mut alerts: HashMap<(String, String), Alert> = HashMap::new();
    let mut owner = String::new();
    let mut seen_calls = HashMap::new();
    let mut recovering_devices = HashSet::new();
    let mut refresh = tokio::time::interval(Duration::from_secs(2));
    loop {
        tokio::select! {
            _ = stop.changed() => { break; }
            changed = changes.recv() => {
                if matches!(changed, Err(tokio::sync::broadcast::error::RecvError::Closed)) { break; }
                // Lagged updates are recovered by rescanning current call state.
            }
            _ = refresh.tick() => {}
            signal = owners.next() => {
                let Some(signal) = signal else { break };
                let args = signal.args()?;
                if args.name().as_str() != BUS { continue }
                alerts.clear(); owner.clear();
            }
            signal = actions.next() => {
                let Some(signal) = signal else { break };
                if signal.header().sender().is_none_or(|s| s.as_str() != owner) { continue }
                let (notification,action): (u32,String) = signal.body().deserialize()?;
                if let Some((device,call)) = alerts.iter().find(|(_,a)| a.notification == notification).map(|(k,_)| k.clone()) {
                    if ["default", "controls"].contains(&action.as_str()) {
                        launch_popup(&device).await;
                    } else if ["answer","reject","hangup", "hold", "unhold"].contains(&action.as_str()) {
                        engine.execute(&device,"call_control",&action,serde_json::json!({"call_id":call}),"native-call-alert").await.ok();
                    }
                }
                continue;
            }
        }
        let mut live = HashMap::new();
        let mut eligible_devices = HashSet::new();
        let mut current_recovering = HashSet::new();
        for device in engine.devices().await {
            if !device.connected
                || device.disabled.iter().any(|f| f == "call_control")
                || !device.capabilities.iter().any(|c| {
                    c.id == "call_control" && c.status == unity_protocol::Availability::Available
                })
            {
                continue;
            }
            eligible_devices.insert(device.id.clone());
            let reconnecting = device
                .state
                .get("call_control")
                .is_some_and(|s| s["reconnecting"] == true);
            if reconnecting {
                current_recovering.insert(device.id.clone());
            }
            if let Some(calls) = device
                .state
                .get("call_control")
                .and_then(|s| s["calls"].as_array())
            {
                for call in calls {
                    if [
                        "ringing",
                        "dialing",
                        "connecting",
                        "select_account",
                        "new",
                        "active",
                        "held",
                    ]
                    .contains(&call["state"].as_str().unwrap_or(""))
                    {
                        live.insert(
                            (
                                device.id.clone(),
                                call["id"].as_str().unwrap_or("").to_owned(),
                            ),
                            (device.name.clone(), call.clone(), reconnecting),
                        );
                    }
                }
            }
        }
        let current_calls: HashSet<_> = live.keys().cloned().collect();
        let now = Instant::now();
        // Match the GTK call-end confirmation: a brief empty snapshot should
        // neither relaunch the window nor undo the user's dismissal. Connection
        // or consent loss invalidates this memory immediately.
        seen_calls.retain(|call: &(String, String), last_seen: &mut Instant| {
            eligible_devices.contains(&call.0)
                && (current_calls.contains(call)
                    || now.duration_since(*last_seen) < Duration::from_millis(2500))
        });
        let new_devices: HashSet<_> = current_calls
            .iter()
            .filter(|call| !seen_calls.contains_key(*call))
            .map(|(device, _)| device.clone())
            .filter(|device| !recovering_devices.contains(device))
            .collect();
        for device in new_devices {
            launch_popup(&device).await;
        }
        for call in current_calls {
            seen_calls.insert(call, now);
        }
        recovering_devices = current_recovering;
        // Never reuse IDs across a desktop notification service restart.
        let current_owner = dbus
            .get_name_owner(zbus::names::BusName::try_from(BUS)?)
            .await
            .map(|o| o.to_string())
            .unwrap_or_default();
        if owner != current_owner {
            alerts.clear();
            owner = current_owner;
        }
        if owner.is_empty() {
            continue;
        }
        // The backend must give returning calls fresh IDs. Reuse only the
        // notification/display for an unambiguous caller on the same device.
        let returning: Vec<_> = live
            .iter()
            .filter(|(key, (_, _, reconnecting))| !*reconnecting && !alerts.contains_key(*key))
            .map(|(key, (_, call, _))| (key.clone(), caller_key(call)))
            .collect();
        for (key, caller) in &returning {
            let candidates: Vec<_> = alerts
                .iter()
                .filter(|(old, alert)| {
                    old.0 == key.0
                        && alert.phase == "reconnecting"
                        && alert.caller == *caller
                        && !live.contains_key(*old)
                })
                .map(|(old, _)| old.clone())
                .collect();
            if candidates.len() == 1
                && returning
                    .iter()
                    .filter(|(other, c)| other.0 == key.0 && c == caller)
                    .count()
                    == 1
            {
                if let Some(alert) = alerts.remove(&candidates[0]) {
                    alerts.insert(key.clone(), alert);
                }
            }
        }
        let removed: Vec<_> = alerts
            .keys()
            .filter(|k| !live.contains_key(*k))
            .cloned()
            .collect();
        for key in removed {
            if let Some(alert) = alerts.remove(&key) {
                proxy
                    .call::<_, _, ()>("CloseNotification", &(alert.notification,))
                    .await
                    .ok();
            }
        }
        for (key, (device, call, reconnecting)) in live {
            let phase = if reconnecting {
                "reconnecting"
            } else {
                call["state"].as_str().unwrap_or("")
            }
            .to_owned();
            if alerts.get(&key).is_some_and(|a| a.phase == phase) {
                continue;
            }
            let replace = alerts.get(&key).map(|a| a.notification).unwrap_or(0);
            let ringing = phase == "ringing";
            let mut controls = if reconnecting {
                vec![]
            } else if ringing {
                vec!["answer", "Answer", "reject", "Reject"]
            } else {
                vec!["hangup", "End call"]
            };
            if !reconnecting
                && call["can_hold"] == true
                && matches!(phase.as_str(), "active" | "held")
            {
                controls.extend(if phase == "held" {
                    ["unhold", "Resume"]
                } else {
                    ["hold", "Hold"]
                });
            }
            controls.extend(["controls", "Call controls"]);
            let caller = call["name"]
                .as_str()
                .filter(|n| !n.is_empty())
                .or_else(|| call["number"].as_str().filter(|n| !n.is_empty()))
                .unwrap_or("Unknown caller");
            let body = format!("{} · {}", escape(&device), escape(caller));
            let hints = HashMap::from([
                ("urgency", Value::from(if ringing { 2u8 } else { 1u8 })),
                ("resident", Value::from(true)),
            ]);
            let result: zbus::Result<u32> = proxy
                .call(
                    "Notify",
                    &(
                        "Unity",
                        replace,
                        "call-start-symbolic",
                        if reconnecting {
                            "Reconnecting call controls…"
                        } else if ringing {
                            "Incoming call"
                        } else if matches!(
                            phase.as_str(),
                            "dialing" | "connecting" | "select_account" | "new"
                        ) {
                            "Calling…"
                        } else if phase == "held" {
                            "Call on hold"
                        } else {
                            "Call in progress"
                        },
                        body,
                        controls,
                        hints,
                        0i32,
                    ),
                )
                .await;
            if let Ok(notification) = result {
                alerts.insert(
                    key,
                    Alert {
                        notification,
                        phase,
                        caller: caller_key(&call),
                    },
                );
            }
        }
    }
    for alert in alerts.values() {
        proxy
            .call::<_, _, ()>("CloseNotification", &(alert.notification,))
            .await
            .ok();
    }
    Ok(())
}
fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}
