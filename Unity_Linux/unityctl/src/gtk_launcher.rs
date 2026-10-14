//! Installed-client launcher: no GTK dependencies and no phone operations.
use anyhow::{bail, Context, Result};
use futures_lite::StreamExt;
use serde_json::Value;
use std::{
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
};
use tokio::process::Command;
use unity_ipc::BUS;

fn checked_script(executable: &Path) -> Result<PathBuf> {
    let prefix = executable
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
        .context("unityctl must be installed under libexec/unity")?;
    let script = prefix.join("share/unity/unity.py");
    let metadata = std::fs::symlink_metadata(&script)
        .context("GTK client is not installed; install the unity-gtk component")?;
    if !metadata.is_file()
        || metadata.mode() & 0o022 != 0
        || ![0, unsafe { libc::geteuid() }].contains(&metadata.uid())
    {
        bail!("GTK client must be a regular file owned by you or root and not writable by other users")
    }
    Ok(script)
}

async fn approve_child(
    bus: &zbus::Connection,
    manager: &zbus::Proxy<'_>,
    pid: u32,
    target: Option<&str>,
) -> Result<()> {
    let json: String = manager.call("ListAccessRequests", &()).await?;
    let requests: Vec<Value> = serde_json::from_str(&json)?;
    let dbus = zbus::fdo::DBusProxy::new(bus).await?;
    for request in requests {
        if request["approved"] == true {
            continue;
        }
        let Some(name) = request["bus_name"].as_str() else {
            continue;
        };
        if target.is_some_and(|target| target != name) {
            continue;
        }
        let name = zbus::names::BusName::try_from(name)?;
        let Ok(credentials) = dbus.get_connection_credentials(name).await else {
            continue;
        };
        // Client labels are untrusted. The bus-reported UID/PID must identify
        // the exact live process launched below, never another Python client.
        if credentials.process_id() != Some(pid)
            || credentials.unix_user_id() != Some(unsafe { libc::geteuid() })
        {
            continue;
        }
        let scopes = request["scopes"]
            .as_array()
            .context("invalid access scopes")?;
        if scopes.is_empty() || scopes.iter().any(|s| s != "read" && s != "control") {
            continue;
        }
        manager
            .call::<_, _, ()>(
                "ApproveAccess",
                &(request["bus_name"].as_str().unwrap(), true),
            )
            .await?;
    }
    Ok(())
}

pub async fn launch(
    bus: &zbus::Connection,
    manager: &zbus::Proxy<'_>,
    target: Option<(&str, &str)>,
) -> Result<()> {
    let script = checked_script(&std::env::current_exe()?)?;
    // Subscribe before spawning: RequestAccess can arrive before spawn returns.
    let mut changes = manager.receive_signal("AccessRequested").await?;
    let dbus = zbus::fdo::DBusProxy::new(bus).await?;
    let mut owners = dbus.receive_name_owner_changed().await?;
    let mut command = Command::new("/usr/bin/python3");
    command.arg(script).kill_on_drop(true);
    if let Some((option, device)) = target {
        command.arg(format!("--{option}={device}"));
    }
    let mut child = command.spawn()?;
    let pid = child.id().context("GTK child has no PID")?;
    // Never inspect or grant requests after the child exits (avoids PID reuse).
    loop {
        tokio::select! {
            biased;
            status = child.wait() => {
                if !status?.success() { bail!("GTK client exited unsuccessfully") }
                return Ok(());
            }
            event = changes.next() => {
                let Some(event) = event else { bail!("Unity access signal stream closed") };
                let (client,): (String,) = event.body().deserialize()?;
                if child.try_wait()?.is_none() {
                    if let Err(error) = approve_child(bus, manager, pid, Some(&client)).await {
                        // A daemon restart can remove its bus name mid-call. The
                        // next name/request event retries; there is no timer loop.
                        eprintln!("Unity launcher: {error}");
                    }
                }
            }
            event = owners.next() => {
                let Some(event) = event else { bail!("Session bus owner stream closed") };
                let args = event.args()?;
                if args.name().as_str() == BUS && args.new_owner().is_some() && child.try_wait()?.is_none() {
                    approve_child(bus, manager, pid, None).await.ok();
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn requires_an_installed_non_writable_client() {
        let root = std::env::temp_dir().join(format!("unity-launcher-{}", std::process::id()));
        let script = root.join("share/unity/unity.py");
        let executable = root.join("libexec/unity/unityctl");
        std::fs::create_dir_all(script.parent().unwrap()).unwrap();
        std::fs::write(&script, "pass").unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert_eq!(checked_script(&executable).unwrap(), script);
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o666)).unwrap();
        assert!(checked_script(&executable).is_err());
        std::fs::remove_file(&script).unwrap();
        std::os::unix::fs::symlink("/usr/bin/python3", &script).unwrap();
        assert!(checked_script(&executable).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
