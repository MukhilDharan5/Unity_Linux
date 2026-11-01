//! A user service can start before GNOME imports its graphical environment.
use std::{collections::HashMap, time::Duration};

pub(super) async fn environment() -> HashMap<String, String> {
    const KEYS: &[&str] = &[
        "DISPLAY",
        "WAYLAND_DISPLAY",
        "XDG_RUNTIME_DIR",
        "DBUS_SESSION_BUS_ADDRESS",
        "XDG_SESSION_TYPE",
        "XDG_CURRENT_DESKTOP",
    ];
    let mut values: HashMap<_, _> = KEYS
        .iter()
        .filter_map(|key| {
            std::env::var(key)
                .ok()
                .filter(|v| !v.is_empty())
                .map(|v| ((*key).to_owned(), v))
        })
        .collect();
    if !values.contains_key("WAYLAND_DISPLAY") && !values.contains_key("DISPLAY") {
        if let Ok(Ok(output)) = tokio::time::timeout(
            Duration::from_secs(2),
            tokio::process::Command::new("systemctl")
                .args(["--user", "show-environment"])
                .kill_on_drop(true)
                .output(),
        )
        .await
        {
            if output.status.success() {
                for line in String::from_utf8_lossy(&output.stdout).lines() {
                    if let Some((key, value)) = line.split_once('=') {
                        if KEYS.contains(&key) && !value.is_empty() {
                            values.insert(key.into(), value.into());
                        }
                    }
                }
            }
        }
    }
    values
}
