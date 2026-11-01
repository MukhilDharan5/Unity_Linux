//! Output volume is separate from player metadata and audio routing. No echo setters.
use crate::Engine;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    process::Command,
    sync::{watch, Mutex, Notify},
    time::{interval, timeout},
};
use unity_protocol::{Availability, Message};
use unity_transport::Workload;
#[derive(Default)]
pub(super) struct Backend {
    mutation: Mutex<VecDeque<(String, String, Instant)>>,
    refresh: Notify,
}
async fn pactl(args: &[&str]) -> Result<String> {
    let output = timeout(
        Duration::from_secs(3),
        Command::new("pactl").args(args).kill_on_drop(true).output(),
    )
    .await
    .context("Sound server did not respond")??;
    if !output.status.success() {
        bail!(
            "Sound server unavailable: {}",
            String::from_utf8_lossy(&output.stderr)
                .chars()
                .take(256)
                .collect::<String>()
        );
    }
    if output.stdout.len() > 2 * 1024 * 1024 {
        bail!("Sound server response is too large")
    }
    Ok(String::from_utf8(output.stdout)?.trim().to_owned())
}
async fn output() -> Result<(String, String, Value)> {
    let name = pactl(&["get-default-sink"]).await?;
    let sinks: Value = serde_json::from_str(&pactl(&["--format=json", "list", "sinks"]).await?)?;
    let sink = sinks
        .as_array()
        .context("invalid output list")?
        .iter()
        .find(|s| s["name"] == name)
        .context("Default output is unavailable")?;
    let index = sink["index"]
        .as_u64()
        .filter(|i| *i <= u32::MAX as u64)
        .context("invalid output index")?
        .to_string();
    let output = hex::encode(Sha256::digest(format!("{index}\0{name}")))[..32].to_owned();
    let channels = sink["volume"]
        .as_object()
        .context("missing output volume")?;
    let levels: Vec<_> = channels
        .values()
        .filter_map(|c| c["value"].as_u64())
        .collect();
    if levels.is_empty() || levels.len() > 32 || levels.iter().any(|v| *v > u32::MAX as u64) {
        bail!("invalid output volume")
    }
    let percent =
        (levels.iter().sum::<u64>() as f64 / levels.len() as f64 / 65536.0 * 100.0).round() as u64;
    let description = sink["description"]
        .as_str()
        .unwrap_or(&name)
        .chars()
        .take(128)
        .collect::<String>();
    let muted = sink["mute"].as_bool().context("missing mute state")?;
    Ok((
        index,
        name,
        json!({"origin":"desktop","available":true,"output":output,"name":description,"volume":percent.min(100),"boosted":percent>100,"muted":muted,"error":""}),
    ))
}
fn allowed(device: &crate::Device) -> bool {
    device.connected
        && !device
            .disabled
            .iter()
            .any(|f| matches!(f.as_str(), "sound" | "media"))
        && device
            .capabilities
            .iter()
            .any(|c| c.id == "sound" && c.status == Availability::Available)
}
async fn authorize(engine: &Engine, device: &str, generation: &str, request: &str) -> Result<()> {
    let state = engine.state.lock().await;
    if !state.devices.get(device).is_some_and(allowed)
        || !state
            .sessions
            .get(device)
            .is_some_and(|s| s.generation(generation).is_some())
        || state
            .operations
            .get(request)
            .is_some_and(|o| o.status != "running")
    {
        bail!("Sound permission or connection changed before dispatch")
    }
    Ok(())
}
impl Backend {
    pub async fn control(
        &self,
        engine: &Engine,
        device: &str,
        generation: &str,
        action: &str,
        data: &Value,
        request: &str,
    ) -> Result<Value> {
        let mut commands = self.mutation.lock().await;
        let fingerprint = serde_json::to_string(&json!([action, data]))?;
        authorize(engine, device, generation, request).await?;
        commands.retain(|(_, _, time)| time.elapsed() < Duration::from_secs(600));
        if let Some((_, old, _)) = commands.iter().find(|(id, _, _)| id == request) {
            if old != &fingerprint {
                bail!("Replayed sound request changed contents")
            }
            return Ok(json!({"state":"duplicate_ignored"}));
        }
        if commands.len() >= 256 {
            commands.pop_front();
        }
        commands.push_back((request.into(), fingerprint, Instant::now()));
        if action == "refresh" {
            self.refresh.notify_one();
            return Ok(json!({"state":"refreshed"}));
        }
        let (index, name, before) = output().await?;
        if data["output"] != before["output"] {
            bail!("Laptop audio output changed; use its current volume control")
        }
        if pactl(&["get-default-sink"]).await? != name {
            bail!("Laptop audio output changed before dispatch")
        }
        authorize(engine, device, generation, request).await?;
        match action {
            "volume" => {
                let volume = data["volume"]
                    .as_u64()
                    .filter(|v| *v <= 100)
                    .context("invalid volume")?;
                // Absolute setters and exact output IDs make repeated requests idempotent.
                if before["volume"] != volume || before["boosted"] == true {
                    pactl(&["set-sink-volume", &index, &format!("{volume}%")]).await?;
                }
            }
            "mute" => {
                let mute = data["muted"].as_bool().context("invalid mute state")?;
                if before["muted"] != mute {
                    pactl(&["set-sink-mute", &index, if mute { "1" } else { "0" }]).await?;
                }
            }
            _ => bail!("Unsupported sound control"),
        }
        let (_, _, current) = output().await?;
        self.refresh.notify_one();
        Ok(json!({"state":"submitted_to_laptop_output","sound":current}))
    }
    pub async fn run(&self, engine: Arc<Engine>, mut stop: watch::Receiver<bool>) -> Result<()> {
        let mut ticks = interval(Duration::from_secs(1));
        let mut sent = HashMap::<(String, String), Value>::new();
        loop {
            tokio::select! { _=stop.changed()=>break,_=ticks.tick()=>{},_=self.refresh.notified()=>{} }
            if *stop.borrow() {
                break;
            }
            if !engine.state.lock().await.devices.values().any(allowed) {
                sent.clear();
                continue;
            }
            // Keep older polling snapshots ahead of mutation replies.
            let _guard = self.mutation.lock().await;
            let snapshot = match output().await {
                Ok((_, _, state)) => state,
                Err(error) => {
                    json!({"origin":"desktop","available":false,"volume":0,"muted":false,"output":"","name":"Laptop output","error":error.to_string().chars().take(128).collect::<String>()})
                }
            };
            let mut state = engine.state.lock().await;
            let devices: Vec<_> = state
                .devices
                .iter()
                .filter(|(_, d)| allowed(d))
                .map(|(id, _)| id.clone())
                .collect();
            let mut live = Vec::new();
            for id in devices {
                if let Some(link) = state
                    .sessions
                    .get(&id)
                    .and_then(|s| s.select(Workload::Control))
                {
                    let key = (id.clone(), link.generation.clone());
                    live.push(key.clone());
                    if sent.get(&key) != Some(&snapshot)
                        && link
                            .tx
                            .try_send(Message::Event {
                                feature: "sound".into(),
                                data: snapshot.clone(),
                            })
                            .is_ok()
                    {
                        sent.insert(key, snapshot.clone());
                    }
                }
                let d = state.devices.get_mut(&id).unwrap();
                if d.state.get("desktop_sound") != Some(&snapshot) {
                    d.state.insert("desktop_sound".into(), snapshot.clone());
                    engine.changed.send(id).ok();
                }
            }
            sent.retain(|key, _| live.contains(key));
        }
        Ok(())
    }
}
