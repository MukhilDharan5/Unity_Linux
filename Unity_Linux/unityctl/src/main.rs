use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use futures_lite::StreamExt;
use serde_json::Value;
use unity_ipc::{device_path, BUS, ROOT};
mod gtk_launcher;
#[derive(Parser)]
#[command(version, about = "Unity headless client of the public D-Bus API")]
struct Args {
    #[command(subcommand)]
    cmd: Command,
}
#[derive(Subcommand)]
enum Command {
    /// Open the installed GTK client and grant access only to its verified child process.
    LaunchGtk {
        /// Open only the live-call popup for this paired device.
        #[arg(long, conflicts_with_all = ["open_messages", "open_notifications"])]
        call_popup: Option<String>,
        #[arg(long, conflicts_with = "open_notifications")]
        open_messages: Option<String>,
        #[arg(long)]
        open_notifications: Option<String>,
    },
    List,
    Status {
        device: String,
    },
    Capabilities {
        device: String,
    },
    Battery {
        device: String,
    },
    Network {
        device: String,
    },
    Discover {
        #[arg(default_value_t = 120)]
        seconds: u32,
    },
    Pairing,
    Pair {
        device: String,
        #[arg(long)]
        reject: bool,
    },
    Unpair {
        device: String,
    },
    Clients,
    Authorize {
        client: String,
        #[arg(long)]
        revoke: bool,
    },
    Diagnose,
    /// Configure or control the local Firefox extension bridge.
    Firefox {
        #[command(subcommand)]
        command: FirefoxCommand,
    },
    Send {
        device: String,
        path: String,
    },
    Media {
        device: String,
        action: String,
        #[arg(default_value_t = 0)]
        position: u64,
    },
    Run {
        device: String,
        feature: String,
        action: String,
        #[arg(default_value = "{}")]
        json: String,
    },
    Feature {
        device: String,
        feature: String,
        #[arg(long)]
        disable: bool,
    },
}
#[derive(Subcommand)]
enum FirefoxCommand {
    Status,
    Enable,
    Disable,
    Restore {
        profile: String,
        /// Omit to restore the last active page.
        context: Option<String>,
    },
}
fn print_json(s: &str) {
    if let Ok(v) = serde_json::from_str::<Value>(s) {
        println!(
            "{}",
            serde_json::to_string_pretty(&v).unwrap_or_else(|_| s.into())
        )
    } else {
        println!("{s}")
    }
}
#[tokio::main(worker_threads = 1)]
async fn main() -> Result<()> {
    let a = Args::parse();
    let bus = zbus::Connection::session()
        .await
        .context("Could not connect to the session bus")?;
    let manager = zbus::Proxy::new(&bus, BUS, ROOT, "org.unityconnect.Manager1").await?;
    match a.cmd {
        Command::Firefox { command } => match command {
            FirefoxCommand::Status => {
                let status: String = manager.call("FirefoxStatus", &()).await?;
                print_json(&status);
            }
            FirefoxCommand::Enable | FirefoxCommand::Disable => {
                manager
                    .call::<_, _, ()>(
                        "SetFirefoxEnabled",
                        &(matches!(command, FirefoxCommand::Enable),),
                    )
                    .await?;
            }
            FirefoxCommand::Restore { profile, context } => {
                let id: String = manager
                    .call(
                        "RestoreFirefoxContext",
                        &(profile, context.unwrap_or_default()),
                    )
                    .await?;
                println!("{id}");
            }
        },
        Command::LaunchGtk {
            call_popup,
            open_messages,
            open_notifications,
        } => {
            let target = call_popup
                .as_ref()
                .map(|device| ("call-popup", device))
                .or_else(|| {
                    open_messages
                        .as_ref()
                        .map(|device| ("open-messages", device))
                })
                .or_else(|| {
                    open_notifications
                        .as_ref()
                        .map(|device| ("open-notifications", device))
                });
            if let Some((_, device)) = target {
                anyhow::ensure!(
                    device.len() == 32
                        && device
                            .bytes()
                            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)),
                    "invalid device ID"
                );
            }
            gtk_launcher::launch(
                &bus,
                &manager,
                target.map(|(option, device)| (option, device.as_str())),
            )
            .await?
        }
        Command::List => {
            let paths: Vec<zbus::zvariant::OwnedObjectPath> =
                manager.call("ListDevices", &()).await?;
            for path in paths {
                let p = zbus::Proxy::new(&bus, BUS, path, "org.unityconnect.Device1").await?;
                let id: String = p.get_property("Id").await?;
                let name: String = p.get_property("Name").await?;
                let connected: bool = p.get_property("Connected").await?;
                println!(
                    "{id}\t{name}\t{}",
                    if connected {
                        "connected"
                    } else {
                        "disconnected"
                    }
                );
            }
        }
        Command::Discover { seconds } => {
            manager
                .call::<_, _, ()>("StartDiscovery", &(seconds,))
                .await?;
            let s: String = manager.call("PairingInfo", &()).await?;
            print_json(&s)
        }
        Command::Pairing => {
            let s: String = manager.call("PairingInfo", &()).await?;
            print_json(&s)
        }
        Command::Pair { device, reject } => {
            manager
                .call::<_, _, ()>("ApprovePairing", &(device, !reject))
                .await?;
        }
        Command::Unpair { device } => {
            manager.call::<_, _, ()>("Unpair", &(device,)).await?;
        }
        Command::Clients => {
            let s: String = manager.call("ListAccessRequests", &()).await?;
            print_json(&s)
        }
        Command::Authorize { client, revoke } => {
            manager
                .call::<_, _, ()>("ApproveAccess", &(client, !revoke))
                .await?;
        }
        Command::Diagnose => {
            let s: String = manager.call("Diagnostics", &()).await?;
            print_json(&s)
        }
        Command::Capabilities { device } => {
            let p = device_proxy(&bus, &device).await?;
            let s: String = p.get_property("CapabilitiesJson").await?;
            print_json(&s)
        }
        Command::Status { device } => show_state(&bus, &device, None).await?,
        Command::Battery { device } => show_state(&bus, &device, Some("battery")).await?,
        Command::Network { device } => show_state(&bus, &device, Some("network")).await?,
        Command::Feature {
            device,
            feature,
            disable,
        } => {
            let p = device_proxy(&bus, &device).await?;
            p.call::<_, _, ()>("SetFeatureEnabled", &(feature, !disable))
                .await?;
        }
        Command::Send { device, path } => {
            let p = device_proxy(&bus, &device).await?;
            let path = std::fs::canonicalize(path)?;
            execute(
                &bus,
                &p,
                "files",
                "send",
                &serde_json::json!({"path":path}).to_string(),
            )
            .await?
        }
        Command::Media {
            device,
            action,
            position,
        } => {
            let p = device_proxy(&bus, &device).await?;
            execute(
                &bus,
                &p,
                "media",
                &action,
                &serde_json::json!({"position":position}).to_string(),
            )
            .await?
        }
        Command::Run {
            device,
            feature,
            action,
            json,
        } => {
            let p = device_proxy(&bus, &device).await?;
            execute(&bus, &p, &feature, &action, &json).await?
        }
    }
    Ok(())
}
async fn device_proxy<'a>(bus: &'a zbus::Connection, id: &str) -> Result<zbus::Proxy<'a>> {
    if !id.len().eq(&32) || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
        anyhow::bail!("expected device ID from unityctl list")
    };
    Ok(zbus::Proxy::new(bus, BUS, device_path(id), "org.unityconnect.Device1").await?)
}
async fn execute(
    bus: &zbus::Connection,
    p: &zbus::Proxy<'_>,
    feature: &str,
    action: &str,
    json: &str,
) -> Result<()> {
    let path: String = p.call("Execute", &(feature, action, json)).await?;
    let op = zbus::Proxy::new(bus, BUS, path, "org.unityconnect.Operation1").await?;
    // Subscribe before reading state so a completion signal cannot be missed.
    let mut changes = op.receive_signal("Changed").await?;
    let deadline = tokio::time::Instant::now()
        + std::time::Duration::from_secs(if feature == "files" && action == "send" {
            900
        } else {
            65
        });
    loop {
        let s: String = op.call("GetState", &()).await?;
        let v: Value = serde_json::from_str(&s)?;
        if v["status"] != "running" {
            print_json(&s);
            if v["status"] != "completed" {
                anyhow::bail!("operation failed")
            };
            break;
        }
        if tokio::time::timeout_at(deadline, changes.next())
            .await?
            .is_none()
        {
            anyhow::bail!("operation signal stream closed")
        }
    }
    Ok(())
}

async fn show_state(bus: &zbus::Connection, device: &str, feature: Option<&str>) -> Result<()> {
    let p = device_proxy(bus, device).await?;
    let s: String = p.call("GetState", &()).await?;
    if let Some(feature) = feature {
        let state: Value = serde_json::from_str(&s)?;
        print_json(&state["state"][feature].to_string())
    } else {
        print_json(&s)
    }
    Ok(())
}
