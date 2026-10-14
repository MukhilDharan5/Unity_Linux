//! On-demand PulseAudio/PipeWire routing. No polling or capture while disabled.
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, path::PathBuf, process::Stdio, time::Duration};
use tokio::{
    io::AsyncReadExt,
    process::{Child, Command},
    sync::watch,
    time::timeout,
};

pub const RATE: u32 = 48_000;
pub const CHANNELS: u32 = 2;
pub const FRAMES: u32 = 480;
pub const PCM_BYTES: usize = (FRAMES * CHANNELS * 2) as usize;
pub const PACKET_BYTES: usize = 8 + PCM_BYTES;
type PacketReceiver = watch::Receiver<Option<Vec<u8>>>;
type CaptureTask = tokio::task::JoinHandle<Result<()>>;

async fn pactl(args: &[&str]) -> Result<String> {
    let output = timeout(
        Duration::from_secs(3),
        Command::new("pactl").args(args).kill_on_drop(true).output(),
    )
    .await
    .context("Audio server did not respond")??;
    if !output.status.success() {
        bail!(
            "Audio routing failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(String::from_utf8(output.stdout)?.trim().to_string())
}
async fn sinks() -> Result<HashMap<String, String>> {
    Ok(pactl(&["list", "short", "sinks"])
        .await?
        .lines()
        .filter_map(|line| {
            let mut columns = line.split_whitespace();
            Some((columns.next()?.into(), columns.next()?.into()))
        })
        .collect())
}
async fn inputs() -> Result<Vec<(String, String)>> {
    Ok(pactl(&["list", "short", "sink-inputs"])
        .await?
        .lines()
        .filter_map(|line| {
            let mut columns = line.split_whitespace();
            Some((columns.next()?.into(), columns.next()?.into()))
        })
        .collect())
}

#[derive(Serialize, Deserialize)]
struct SavedRoute {
    sink: String,
    module: String,
    previous: String,
    inputs: HashMap<String, String>,
}
pub struct Capture {
    saved: SavedRoute,
    journal: PathBuf,
    child: Option<Child>,
}
impl Capture {
    pub async fn recover(dir: &std::path::Path) -> Result<()> {
        let journal = dir.join("audio-route.json");
        if journal.exists() {
            let saved: SavedRoute = serde_json::from_slice(&std::fs::read(&journal)?)?;
            Capture {
                saved,
                journal,
                child: None,
            }
            .restore()
            .await?;
        }
        Ok(())
    }
    pub async fn open(dir: &std::path::Path) -> Result<Self> {
        Self::recover(dir).await?;
        let previous = pactl(&["get-default-sink"])
            .await
            .context("Install pulseaudio-utils and run PipeWire-Pulse or PulseAudio")?;
        let original_sinks = sinks().await?;
        let original_inputs = inputs()
            .await?
            .into_iter()
            .filter_map(|(input, sink)| Some((input, original_sinks.get(&sink)?.clone())))
            .collect();
        let sink = format!("unity_phone_{}", uuid::Uuid::new_v4().simple());
        let module = pactl(&[
            "load-module",
            "module-null-sink",
            &format!("sink_name={sink}"),
            "sink_properties=device.description=Unity_Phone",
            "rate=48000",
            "channels=2",
            "format=s16le",
        ])
        .await?;
        let mut capture = Self {
            saved: SavedRoute {
                sink,
                module,
                previous,
                inputs: original_inputs,
            },
            journal: dir.join("audio-route.json"),
            child: None,
        };
        // Journal before changing the desktop output, for recovery after a crash.
        let result = async {
            unity_security::write_private(&capture.journal, &serde_json::to_vec(&capture.saved)?)?;
            capture.child = Some(
                Command::new("parec")
                    .args([
                        "--raw",
                        "--format=s16le",
                        "--rate=48000",
                        "--channels=2",
                        "--latency-msec=10",
                        "--process-time-msec=5",
                        &format!("--device={}.monitor", capture.saved.sink),
                    ])
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::null())
                    .kill_on_drop(true)
                    .spawn()
                    .context("Install pulseaudio-utils (parec)")?,
            );
            pactl(&["set-default-sink", &capture.saved.sink]).await?;
            for input in capture.saved.inputs.keys() {
                // A stream may disappear during setup; the new default handles new streams.
                pactl(&["move-sink-input", input, &capture.saved.sink])
                    .await
                    .ok();
            }
            Ok::<_, anyhow::Error>(())
        }
        .await;
        if let Err(error) = result {
            if let Err(restore) = capture.restore().await {
                return Err(
                    error.context(format!("Laptop output restoration also failed: {restore}"))
                );
            }
            return Err(error);
        }
        Ok(capture)
    }
    pub fn packets(&mut self) -> Result<(PacketReceiver, CaptureTask)> {
        let mut source = self
            .child
            .as_mut()
            .context("capture not started")?
            .stdout
            .take()
            .context("missing capture output")?;
        let (tx, rx) = watch::channel(None);
        let task = tokio::spawn(async move {
            let mut sequence = 0u64;
            loop {
                let mut packet = vec![0u8; PACKET_BYTES];
                packet[..8].copy_from_slice(&sequence.to_be_bytes());
                timeout(Duration::from_secs(3), source.read_exact(&mut packet[8..]))
                    .await
                    .context("Laptop audio capture stalled")??;
                // One latest packet, never a growing queue behind a slow network.
                if tx.send(Some(packet)).is_err() {
                    return Ok(());
                }
                sequence += 1;
            }
        });
        Ok((rx, task))
    }
    pub async fn restore(&mut self) -> Result<()> {
        if let Some(mut child) = self.child.take() {
            child.kill().await.ok();
            child.wait().await.ok();
        }
        let available = sinks().await?;
        if let Some((index, _)) = available.iter().find(|(_, name)| **name == self.saved.sink) {
            let current = pactl(&["get-default-sink"]).await?;
            let fallback = if current == self.saved.sink {
                if available.values().any(|name| *name == self.saved.previous) {
                    pactl(&["set-default-sink", &self.saved.previous]).await?;
                    self.saved.previous.clone()
                } else {
                    let next = available
                        .values()
                        .find(|name| **name != self.saved.sink)
                        .context("No laptop audio output available for restoration")?
                        .clone();
                    pactl(&["set-default-sink", &next]).await?;
                    next
                }
            } else {
                current
            };
            for (input, sink) in inputs().await? {
                if sink != *index {
                    continue;
                }
                let destination = self
                    .saved
                    .inputs
                    .get(&input)
                    .filter(|name| available.values().any(|candidate| candidate == *name))
                    .unwrap_or(&fallback);
                pactl(&["move-sink-input", &input, destination]).await.ok();
            }
            // PulseAudio can recycle module indices after restart; verify ownership too.
            let modules = pactl(&["list", "short", "modules"]).await?;
            if modules.lines().any(|line| {
                line.split_whitespace().next() == Some(&self.saved.module)
                    && line
                        .split_whitespace()
                        .any(|arg| arg == format!("sink_name={}", self.saved.sink))
            }) {
                pactl(&["unload-module", &self.saved.module]).await?;
            }
        }
        if self.journal.exists() {
            std::fs::remove_file(&self.journal)?;
        }
        Ok(())
    }
}
