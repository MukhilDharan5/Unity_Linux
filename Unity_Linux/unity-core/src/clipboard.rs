//! Daemon-owned clipboard sync, independent of GTK and notification services.
use crate::Engine;
use anyhow::{bail, Context, Result};
use std::{collections::HashMap, process::Stdio, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    process::Command,
    sync::watch,
    time::timeout,
};
use unity_protocol::{Availability, Message, MAX_TEXT};

pub(super) struct Backend {
    incoming: watch::Sender<Option<(String, String, String)>>,
    results: watch::Sender<(String, String, String, bool)>,
}
impl Default for Backend {
    fn default() -> Self {
        Self {
            incoming: watch::channel(None).0,
            results: watch::channel((String::new(), String::new(), String::new(), false)).0,
        }
    }
}
impl Backend {
    pub fn result(&self, device: &str, generation: &str, operation: &str, ok: bool) {
        self.results
            .send_replace((device.into(), generation.into(), operation.into(), ok));
    }
    pub fn received(&self, device: &str, generation: &str, text: &str) {
        // Coalesce bursts to the newest clipboard; never block a transport reader.
        self.incoming
            .send_replace(Some((device.into(), generation.into(), text.into())));
    }
    pub async fn run(&self, engine: Arc<Engine>, mut stop: watch::Receiver<bool>) {
        let (local, mut changes) = watch::channel(None::<String>);
        let mut incoming = self.incoming.subscribe();
        let mut results = self.results.subscribe();
        let mut activity = engine.changed.subscribe();
        let observer = tokio::spawn(observe(local, stop.clone()));
        let mut current = None::<String>;
        let mut remote_source = None::<(String, String, String)>;
        let mut pending_write = None::<(String, String, String)>;
        let mut next_write = tokio::time::Instant::now();
        let mut delivered = HashMap::<String, String>::new();
        let mut pending = HashMap::<String, (String, String, tokio::time::Instant)>::new();
        let mut retry = tokio::time::interval(Duration::from_millis(100));
        retry.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                biased;
                _ = stop.changed() => break,
                result = incoming.changed() => {
                    if result.is_err() { break; }
                    let Some((device, generation, text)) = incoming.borrow_and_update().clone() else { continue; };
                    let allowed = {
                        let state = engine.state.lock().await;
                        state.devices.get(&device).is_some_and(|d| d.connected && !d.disabled.iter().any(|f| f == "clipboard")
                            && d.capabilities.iter().any(|c| c.id == "clipboard" && c.status == Availability::Available))
                            && state.sessions.get(&device).and_then(|s| s.generation(&generation)).is_some()
                    };
                    if !allowed { continue; }
                    remote_source = Some((text.clone(), device.clone(), generation.clone()));
                    if current.as_ref() != Some(&text) {
                        // Record before copying, so the resulting selection event is not echoed.
                        current = Some(text.clone()); delivered.clear(); pending.clear();
                        pending_write = Some((device.clone(), generation.clone(), text));
                        next_write = tokio::time::Instant::now();
                    }
                    delivered.insert(device, generation);
                },
                result = changes.changed() => {
                    if result.is_err() { break; }
                    let text = changes.borrow_and_update().clone();
                    if text != current {
                        current = text; delivered.clear(); pending.clear(); pending_write = None;
                        if let Some((remote, device, generation)) = remote_source.as_ref() {
                            if current.as_ref() == Some(remote) { delivered.insert(device.clone(), generation.clone()); }
                            else if current.is_some() { remote_source = None; }
                        }
                    }
                },
                _ = results.changed() => {
                    let (device, generation, operation, ok) = results.borrow_and_update().clone();
                    if ok && pending.get(&device).is_some_and(|(link, id, _)| *link == generation && *id == operation) {
                        pending.remove(&device);
                        delivered.insert(device, generation);
                    }
                },
                _ = activity.recv() => {},
                _ = retry.tick() => {},
            }
            if *stop.borrow() {
                break;
            }
            if next_write <= tokio::time::Instant::now() {
                if let Some((device, generation, text)) = pending_write.as_ref() {
                    let allowed = {
                        let state = engine.state.lock().await;
                        state.devices.get(device).is_some_and(|d| {
                            d.connected
                                && !d.disabled.iter().any(|f| f == "clipboard")
                                && d.capabilities.iter().any(|c| {
                                    c.id == "clipboard" && c.status == Availability::Available
                                })
                        }) && state
                            .sessions
                            .get(device)
                            .and_then(|s| s.generation(generation))
                            .is_some()
                    };
                    if allowed {
                        changes.borrow_and_update();
                        match copy(text).await {
                            Ok(()) => {
                                pending_write = None;
                            }
                            Err(error) => {
                                tracing::debug!(%error, "Automatic clipboard write will retry");
                                next_write = tokio::time::Instant::now() + Duration::from_secs(1);
                            }
                        }
                    } else {
                        pending_write = None;
                        current = changes.borrow_and_update().clone();
                        delivered.clear();
                        pending.clear();
                        remote_source = None;
                    }
                }
            }
            let Some(text) = current.as_ref() else {
                continue;
            };
            let state = engine.state.lock().await;
            pending.retain(|device, _| {
                state
                    .devices
                    .get(device)
                    .is_some_and(|d| d.connected && !d.disabled.iter().any(|f| f == "clipboard"))
            });
            delivered.retain(|device, _| {
                state.devices.get(device).is_some_and(|d| {
                    d.connected
                        && !d.disabled.iter().any(|f| f == "clipboard")
                        && d.capabilities
                            .iter()
                            .any(|c| c.id == "clipboard" && c.status == Availability::Available)
                })
            });
            for (id, device) in &state.devices {
                if !device.connected
                    || device.disabled.iter().any(|f| f == "clipboard")
                    || !device
                        .capabilities
                        .iter()
                        .any(|c| c.id == "clipboard" && c.status == Availability::Available)
                {
                    continue;
                }
                let Some(link) = state
                    .sessions
                    .get(id)
                    .and_then(|s| s.select(unity_transport::Workload::Control))
                else {
                    continue;
                };
                if delivered.get(id).is_some_and(|generation| {
                    state
                        .sessions
                        .get(id)
                        .and_then(|s| s.generation(generation))
                        .is_some()
                }) {
                    continue;
                }
                if pending.get(id).is_some_and(|(generation, _, at)| {
                    state
                        .sessions
                        .get(id)
                        .and_then(|s| s.generation(generation))
                        .is_some()
                        && at.elapsed() < Duration::from_secs(3)
                }) {
                    continue;
                }
                let operation = uuid::Uuid::new_v4().simple().to_string();
                let message = Message::Command {
                    id: operation.clone(),
                    feature: "clipboard".into(),
                    action: "set".into(),
                    data: serde_json::json!({"text": text, "automatic": true}),
                };
                if link.tx.try_send(message).is_ok() {
                    pending.insert(
                        id.clone(),
                        (
                            link.generation.clone(),
                            operation,
                            tokio::time::Instant::now(),
                        ),
                    );
                }
            }
        }
        observer.abort();
        observer.await.ok();
    }
}

// wl-paste invokes this directly (no shell). JSON lines frame arbitrary Unicode,
// embedded newlines and empty text, while bounding clipboard data before reading it.
const WATCH_TEXT: &str = r#"import json,os,sys
b=sys.stdin.buffer.read(65281)
s=None
if os.environ.get('CLIPBOARD_STATE') not in ('nil','clear','sensitive') and len(b)<=65280 and b'\x00' not in b:
 try: s=b.decode('utf-8',errors='strict')
 except UnicodeDecodeError: pass
print(json.dumps(s),flush=True)
"#;

// GNOME does not expose data-control. A windowless XWayland client can use
// GDK's native selection-change events, without polling or stealing focus.
const WATCH_XWAYLAND: &str = r#"import gi,json
gi.require_version('Gtk','4.0')
from gi.repository import Gtk,Gdk,GLib
Gtk.init()
display=Gdk.Display.get_default()
if display is None: raise RuntimeError('No X11 clipboard display')
clipboard=display.get_clipboard()
revision=0
def changed(*args):
 global revision
 revision+=1
 current=revision
 def received(clip,result):
  if current!=revision: return
  try: text=clip.read_text_finish(result)
  except GLib.Error: return
  if text is not None and (len(text.encode('utf-8'))>65280 or '\x00' in text): text=None
  print(json.dumps(text),flush=True)
 clipboard.read_text_async(None,received)
clipboard.connect('changed',changed)
changed()
GLib.MainLoop().run()
"#;

async fn observe(tx: watch::Sender<Option<String>>, mut stop: watch::Receiver<bool>) {
    loop {
        let environment = crate::desktop::environment().await;
        let mut result = if environment.contains_key("WAYLAND_DISPLAY") {
            watch_wayland(&tx, &environment, &mut stop).await
        } else {
            Err(anyhow::anyhow!("No graphical clipboard session available"))
        };
        if result.is_err() && !*stop.borrow() && environment.contains_key("DISPLAY") {
            let mut command = Command::new("python3");
            command
                .args(["-c", WATCH_XWAYLAND])
                .envs(&environment)
                .env("GDK_BACKEND", "x11");
            result = watch_output(&tx, &mut command, &mut stop).await;
            if result.is_err() && !*stop.borrow() {
                result = watch_x11(&tx, &environment, &mut stop).await;
            }
        }
        if *stop.borrow() {
            return;
        }
        if let Err(error) = result {
            tracing::debug!(%error, "Clipboard observer will retry");
        }
        tokio::select! { _ = stop.changed() => return, _ = tokio::time::sleep(Duration::from_secs(2)) => {} }
    }
}
async fn watch_wayland(
    tx: &watch::Sender<Option<String>>,
    environment: &HashMap<String, String>,
    stop: &mut watch::Receiver<bool>,
) -> Result<()> {
    let mut command = Command::new("wl-paste");
    command
        .args([
            "--type",
            "text",
            "--no-newline",
            "--watch",
            "python3",
            "-c",
            WATCH_TEXT,
        ])
        .envs(environment);
    watch_output(tx, &mut command, stop).await
}
async fn watch_output(
    tx: &watch::Sender<Option<String>>,
    command: &mut Command,
    stop: &mut watch::Receiver<bool>,
) -> Result<()> {
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()?;
    let mut output = BufReader::new(
        child
            .stdout
            .take()
            .context("Missing clipboard watcher output")?,
    );
    loop {
        let mut line = Vec::new();
        let mut bounded = (&mut output).take((MAX_TEXT * 6 + 16) as u64);
        let count = tokio::select! {
            _ = stop.changed() => return Ok(()),
            count = bounded.read_until(b'\n', &mut line) => count?,
        };
        if count == 0 {
            bail!("Clipboard watcher exited");
        }
        if line.last() != Some(&b'\n') {
            bail!("Clipboard watcher output exceeds limit");
        }
        if let Ok(text) = serde_json::from_slice::<Option<String>>(&line) {
            if text.as_deref().is_none_or(valid_text) {
                tx.send_if_modified(|previous| {
                    if *previous == text {
                        false
                    } else {
                        *previous = text;
                        true
                    }
                });
            }
        }
    }
}
async fn watch_x11(
    tx: &watch::Sender<Option<String>>,
    environment: &HashMap<String, String>,
    stop: &mut watch::Receiver<bool>,
) -> Result<()> {
    let mut tick = tokio::time::interval(Duration::from_millis(100));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! { _ = stop.changed() => return Ok(()), _ = tick.tick() => {} }
        let mut child = Command::new("xclip")
            .args(["-selection", "clipboard", "-out", "-target", "UTF8_STRING"])
            .envs(environment)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()?;
        let mut bytes = Vec::new();
        let result = timeout(Duration::from_millis(500), async {
            child
                .stdout
                .take()
                .context("Missing clipboard output")?
                .take((MAX_TEXT - 255) as u64)
                .read_to_end(&mut bytes)
                .await?;
            Ok::<_, anyhow::Error>(child.wait().await?.success())
        })
        .await;
        if matches!(result, Ok(Ok(true))) {
            if let Ok(text) = String::from_utf8(bytes) {
                if valid_text(&text) {
                    tx.send_if_modified(|previous| {
                        if previous.as_ref() == Some(&text) {
                            false
                        } else {
                            *previous = Some(text);
                            true
                        }
                    });
                }
            }
        }
    }
}
fn valid_text(text: &str) -> bool {
    text.len() <= MAX_TEXT - 256 && !text.contains('\0')
}

pub(super) struct TemporaryFile(pub std::path::PathBuf);
impl Drop for TemporaryFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
        if let Some(parent) = self.0.parent().filter(|p| {
            p.file_name()
                .and_then(|s| s.to_str())
                .is_some_and(unity_protocol::valid_id)
        }) {
            let _ = std::fs::remove_dir(parent);
        }
    }
}

pub(super) async fn copy_file(file: TemporaryFile) -> Result<()> {
    let environment = crate::desktop::environment().await;
    let mut command = if environment.contains_key("WAYLAND_DISPLAY") {
        let mut command = Command::new("wl-copy");
        command.args(["--foreground", "--type", "text/uri-list"]);
        command
    } else if environment.contains_key("DISPLAY") {
        let mut command = Command::new("xclip");
        command.args([
            "-selection",
            "clipboard",
            "-target",
            "text/uri-list",
            "-quiet",
        ]);
        command
    } else {
        bail!("No graphical session is available for clipboard sharing")
    };
    command.envs(environment);
    own_file(&mut command, file).await
}

async fn own_file(command: &mut Command, file: TemporaryFile) -> Result<()> {
    let mut uri = String::from("file://");
    for byte in file.0.as_os_str().as_encoded_bytes() {
        if byte.is_ascii_alphanumeric() || b"/-._~".contains(byte) {
            uri.push(*byte as char);
        } else {
            uri.push_str(&format!("%{byte:02X}"));
        }
    }
    uri.push_str("\r\n");
    let mut child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .context("Install wl-clipboard or xclip for file clipboard support")?;
    timeout(Duration::from_secs(3), async {
        let mut input = child.stdin.take().context("Clipboard input unavailable")?;
        input.write_all(uri.as_bytes()).await?;
        input.shutdown().await?;
        Ok::<_, anyhow::Error>(())
    })
    .await
    .context("Clipboard write timed out")??;
    // Foreground clipboard helpers remain alive until another selection takes
    // ownership. They serve repeated pastes; no paste-once or arbitrary expiry.
    tokio::time::sleep(Duration::from_millis(150)).await;
    if child.try_wait()?.is_some() {
        bail!("Clipboard ownership could not be established")
    }
    tokio::spawn(async move {
        let _file = file;
        let _ = child.wait().await;
        // Dropping the guard removes only this copy's private temporary file.
    });
    Ok(())
}

pub(super) async fn copy(text: &str) -> Result<()> {
    let environment = crate::desktop::environment().await;
    let mut command = if environment.contains_key("WAYLAND_DISPLAY") {
        let mut command = Command::new("wl-copy");
        command.args(["--type", "text/plain;charset=utf-8"]);
        command
    } else if environment.contains_key("DISPLAY") {
        let mut command = Command::new("xclip");
        command.args(["-selection", "clipboard", "-in"]);
        command
    } else {
        bail!("No graphical session is available for clipboard sharing")
    };
    command.envs(environment);
    write(&mut command, text).await
}

async fn write(command: &mut Command, text: &str) -> Result<()> {
    let mut child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .context("Install wl-clipboard on Wayland or xclip on X11 for desktop clipboard sharing")?;
    timeout(Duration::from_secs(3), async {
        let mut input = child.stdin.take().context("Clipboard input unavailable")?;
        input.write_all(text.as_bytes()).await?;
        input.shutdown().await?;
        drop(input);
        if !child.wait().await?.success() {
            bail!("Desktop clipboard service refused the text")
        }
        Ok(())
    })
    .await
    .context("Desktop clipboard write timed out")?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn preserves_unicode_newlines_and_shell_characters_and_reports_failure() {
        let path = std::env::temp_dir().join(format!("unity-clipboard-{}", uuid::Uuid::new_v4()));
        let text = "தமிழ் 😀\n$(touch /should-not-exist) `literal`\n";
        let mut command = Command::new("/usr/bin/tee");
        command.arg(&path);
        write(&mut command, text).await.unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), text);
        std::fs::remove_file(path).unwrap();
        assert!(write(&mut Command::new("/usr/bin/false"), "")
            .await
            .is_err());
    }
}
