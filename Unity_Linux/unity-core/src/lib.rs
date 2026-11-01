//! GUI-free lifecycle, pairing, authorization-neutral command routing and state.
mod artwork;
mod audio;
mod audio_session;
mod authentication;
mod call_alerts;
mod call_audio;
mod call_hfp;
mod clipboard;
mod desktop;
mod desktop_control;
mod desktop_media;
mod firefox;
mod gallery;
mod lan;
mod phone_alerts;
mod phone_media;
mod receipts;
mod screen_session;
mod sound;
use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine as _};
pub use screen_session::ScreenFrame;
use serde::Serialize;
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashMap},
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    net::TcpListener,
    sync::{broadcast, mpsc, oneshot, watch, Mutex, Semaphore},
    time::{interval, timeout},
};
use unity_protocol::{Availability, Capability, Message};
use unity_security::{Identity, Trust, TrustStore};
use unity_transport::{network::LanTransport, Kind, Transport, Workload};
use uuid::Uuid;
#[derive(Clone, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub connected: bool,
    pub capabilities: Vec<Capability>,
    pub state: BTreeMap<String, Value>,
    pub disabled: Vec<String>,
    pub last_error: String,
    #[serde(skip)]
    phone_artwork: BTreeMap<String, Value>,
}
#[derive(Clone, Serialize)]
pub struct Operation {
    pub id: String,
    pub device: String,
    pub feature: String,
    pub action: String,
    pub status: String,
    pub progress: u64,
    pub result: Value,
    pub error: String,
    #[serde(skip)]
    pub owner: String,
    #[serde(skip)]
    pub started: Option<Instant>,
    #[serde(skip)]
    observed: bool,
    #[serde(skip)]
    generation: String,
}
struct Session {
    kind: Kind,
    local_ip: Option<std::net::IpAddr>,
    generation: String,
    started_at: u64,
    network_info: bool,
    biometric_auth: bool,
    tx: mpsc::Sender<Message>,
    stop: watch::Sender<bool>,
}
#[derive(Default)]
struct Sessions(Vec<Session>);
impl Sessions {
    fn select(&self, workload: Workload) -> Option<&Session> {
        let available: Vec<_> = self
            .0
            .iter()
            .filter(|s| !*s.stop.borrow())
            .map(|s| s.kind)
            .collect();
        let kind = unity_transport::select(&available, workload)?;
        self.0.iter().find(|s| s.kind == kind && !*s.stop.borrow())
    }
    fn generation(&self, generation: &str) -> Option<&Session> {
        self.0
            .iter()
            .find(|s| s.generation == generation && !*s.stop.borrow())
    }
    fn status(&self) -> Value {
        let label = |kind| {
            if kind == Kind::Bluetooth {
                "bluetooth"
            } else {
                "lan"
            }
        };
        let links: Vec<_> = [Kind::Bluetooth, Kind::Lan]
            .iter()
            .filter(|k| self.0.iter().any(|s| s.kind == **k && !*s.stop.borrow()))
            .map(|k| label(*k))
            .collect();
        json!({"kind": if links.len() == 2 { "both" } else { links.first().copied().unwrap_or("none") },
            "links": links, "control": self.select(Workload::Control).map(|s| label(s.kind)),
            "bulk": self.select(Workload::Bulk).map(|s| label(s.kind))})
    }
}
struct PendingPair {
    name: String,
    approve: oneshot::Sender<bool>,
    expires: Instant,
}
#[derive(Default)]
struct State {
    call_phones: BTreeMap<String, String>,
    audio: Option<audio_session::AudioSession>,
    screens: HashMap<String, screen_session::ScreenSession>,
    screen_frames: HashMap<String, ScreenFrame>,
    trust: TrustStore,
    devices: BTreeMap<String, Device>,
    sessions: HashMap<String, Sessions>,
    pairs: HashMap<String, PendingPair>,
    operations: BTreeMap<String, Operation>,
    discovery_until: Option<Instant>,
}
pub struct Engine {
    authentication: authentication::Broker,
    firefox: Arc<firefox::Backend>,
    state: Mutex<State>,
    call_audio: Mutex<call_audio::Router>,
    call_hfp: Mutex<call_hfp::Backend>,
    call_hfp_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    call_alerts_stop: watch::Sender<bool>,
    call_alerts_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    call_audio_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    receipts: receipts::Notifications,
    clipboard: clipboard::Backend,
    clipboard_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    sound: sound::Backend,
    sound_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    desktop_media: desktop_media::Backend,
    desktop_media_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    phone_media_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    phone_alerts: phone_alerts::Notifications,
    phone_alerts_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    bluetooth: Mutex<Value>,
    pub identity: Identity,
    pub dir: PathBuf,
    pub changed: broadcast::Sender<String>,
    pub screen_changed: broadcast::Sender<(String, u64)>,
    request_limit: Arc<Semaphore>,
    pub port: u16,
    lan_ports: Mutex<Vec<u16>>,
    limit: Arc<Semaphore>,
}
impl Engine {
    pub fn new(dir: PathBuf, port: u16) -> Result<Arc<Self>> {
        let identity = Identity::load_or_create(&dir)?;
        let trust = TrustStore::load(&dir)?;
        let call_phones = if dir.join("call-phones.json").exists() {
            serde_json::from_slice(&std::fs::read(dir.join("call-phones.json"))?)?
        } else {
            BTreeMap::new()
        };
        let mut devices = BTreeMap::new();
        let toggles: BTreeMap<String, Vec<String>> = if dir.join("features.json").exists() {
            serde_json::from_slice(&std::fs::read(dir.join("features.json"))?)?
        } else {
            BTreeMap::new()
        };
        for (id, t) in &trust.devices {
            devices.insert(
                id.clone(),
                Device {
                    id: id.clone(),
                    name: t.name.clone(),
                    connected: false,
                    capabilities: unity_plugins::capabilities(&[]),
                    state: BTreeMap::new(),
                    phone_artwork: BTreeMap::new(),
                    disabled: toggles.get(id).cloned().unwrap_or_default(),
                    last_error: String::new(),
                },
            );
        }
        let (changed, _) = broadcast::channel(128);
        let (screen_changed, _) = broadcast::channel(16);
        let (call_alerts_stop, _) = watch::channel(false);
        Ok(Arc::new(Self {
            authentication: authentication::Broker::default(),
            firefox: firefox::Backend::new(&dir, changed.clone())?,
            call_audio: Mutex::new(call_audio::Router::default()),
            call_hfp: Mutex::new(call_hfp::Backend::default()),
            call_hfp_task: Mutex::new(None),
            call_alerts_stop,
            call_alerts_task: Mutex::new(None),
            call_audio_task: Mutex::new(None),
            receipts: receipts::Notifications::new(dir.join("received")),
            clipboard: clipboard::Backend::default(),
            clipboard_task: Mutex::new(None),
            sound: sound::Backend::default(),
            sound_task: Mutex::new(None),
            desktop_media: desktop_media::Backend::default(),
            desktop_media_task: Mutex::new(None),
            phone_media_task: Mutex::new(None),
            phone_alerts: phone_alerts::Notifications::new(),
            phone_alerts_task: Mutex::new(None),
            state: Mutex::new(State {
                call_phones,
                trust,
                devices,
                ..Default::default()
            }),
            bluetooth: Mutex::new(json!({"status":"starting","adapters":[]})),
            identity,
            dir,
            changed,
            screen_changed,
            request_limit: Arc::new(Semaphore::new(4)),
            port,
            lan_ports: Mutex::new(vec![port]),
            limit: Arc::new(Semaphore::new(16)),
        }))
    }
    pub async fn shutdown_calls(&self) {
        self.call_alerts_stop.send(true).ok();
        if let Some(mut task) = self.clipboard_task.lock().await.take() {
            if timeout(Duration::from_secs(4), &mut task).await.is_err() {
                task.abort();
            }
        }
        if let Some(mut task) = self.phone_media_task.lock().await.take() {
            if timeout(Duration::from_secs(5), &mut task).await.is_err() {
                task.abort();
            }
        }
        if let Some(mut task) = self.sound_task.lock().await.take() {
            if timeout(Duration::from_secs(7), &mut task).await.is_err() {
                task.abort();
            }
        }
        if let Some(mut task) = self.desktop_media_task.lock().await.take() {
            if timeout(Duration::from_secs(5), &mut task).await.is_err() {
                task.abort();
            }
        }
        if let Some(mut task) = self.call_hfp_task.lock().await.take() {
            if timeout(Duration::from_secs(6), &mut task).await.is_err() {
                task.abort();
                task.await.ok();
            }
        }
        if let Some(mut task) = self.call_audio_task.lock().await.take() {
            // Finish an in-flight audio mutation before final cleanup.
            if timeout(Duration::from_secs(25), &mut task).await.is_err() {
                task.abort();
                task.await.ok();
            }
        }
        if let Some(mut task) = self.call_alerts_task.lock().await.take() {
            if timeout(Duration::from_secs(3), &mut task).await.is_err() {
                task.abort();
            }
        }
        self.stop_call_audio("").await;
        if let Some(mut task) = self.phone_alerts_task.lock().await.take() {
            if timeout(Duration::from_secs(5), &mut task).await.is_err() {
                task.abort();
            }
        }
    }
    async fn stop_call_audio(&self, device: &str) {
        if let Err(error) = self.call_audio.lock().await.stop(&self.dir, device).await {
            tracing::warn!(%error, "Call audio cleanup pending");
        }
        let mut state = self.state.lock().await;
        for (id, d) in state.devices.iter_mut() {
            if device.is_empty() || id == device {
                d.state.remove("call_audio");
            }
        }
    }
    async fn publish_hfp(&self) {
        let backend = self.call_hfp.lock().await;
        let mut state = self.state.lock().await;
        let bindings = state.call_phones.clone();
        let mut stopped = Vec::new();
        for (id, d) in &mut state.devices {
            if !d.connected
                || d.disabled.iter().any(|f| f == "call_control")
                || !d
                    .capabilities
                    .iter()
                    .any(|c| c.id == "call_control" && c.status == Availability::Available)
                || d.state
                    .get("call_control")
                    .is_none_or(|c| c["backend"] != "bluetooth_hfp")
            {
                continue;
            }
            let mut value = backend.snapshot(bindings.get(id).map(String::as_str));
            let previous = d.state.get("call_control").unwrap();
            // A failed/restarting HFP service cannot confirm that a cellular
            // call ended. The independent phone-state signal can corroborate
            // the ongoing call; preserve its display, never its authority.
            let phone_in_call = !d.disabled.iter().any(|f| f == "call_notifications")
                && d.capabilities
                    .iter()
                    .any(|c| c.id == "call_notifications" && c.status == Availability::Available)
                && d.state
                    .get("call_notifications")
                    .is_some_and(|c| matches!(c["state"].as_str(), Some("ringing" | "offhook")));
            if value["calls"]
                .as_array()
                .is_some_and(|calls| calls.is_empty())
                && (value["error"].as_str().is_some_and(|e| !e.is_empty())
                    || previous["reconnecting"] == true)
                && previous["address"] == value["address"]
                && previous["calls"]
                    .as_array()
                    .is_some_and(|calls| !calls.is_empty())
                && phone_in_call
            {
                value["calls"] = previous["calls"].clone();
                value["reconnecting"] = json!(true);
                value["error"] = json!("Reconnecting Bluetooth call controls…");
            }
            if d.state.get("call_control") != Some(&value) {
                if value["calls"].as_array().is_none_or(|c| c.is_empty())
                    || value["route"] != "bluetooth"
                    || value["reconnecting"] == true
                {
                    stopped.push(id.clone());
                }
                d.state.insert("call_control".into(), value);
                self.changed.send(id.clone()).ok();
            }
        }
        drop(state);
        drop(backend);
        for id in stopped {
            self.stop_call_audio(&id).await;
        }
    }
    async fn hfp_operation(
        &self,
        device: &str,
        action: &str,
        data: &Value,
        operation: &str,
    ) -> Result<Value> {
        let mut backend = self.call_hfp.lock().await;
        let state = self.state.lock().await;
        if state
            .operations
            .get(operation)
            .is_none_or(|o| o.status != "running")
        {
            bail!("Call operation cancelled");
        }
        let d = state.devices.get(device).context("Device unavailable")?;
        if !d.connected
            || d.disabled.iter().any(|f| f == "call_control")
            || !d
                .capabilities
                .iter()
                .any(|c| c.id == "call_control" && c.status == Availability::Available)
        {
            bail!("Call management unavailable");
        }
        let address = state.call_phones.get(device).cloned();
        let on_computer = d
            .state
            .get("call_audio")
            .is_some_and(|a| matches!(a["state"].as_str(), Some("active" | "waiting")));
        drop(state);
        if action == "route" && data["route"] == "phone" {
            self.stop_call_audio(device).await;
        }
        let result = match action {
            "hfp_devices" => backend.devices(),
            "hfp_select" => {
                let address = data["address"].as_str().unwrap().to_ascii_uppercase();
                timeout(Duration::from_secs(8), backend.select(&address)).await??;
                let mut state = self.state.lock().await;
                let mut bindings = state.call_phones.clone();
                bindings.insert(device.into(), address.clone());
                unity_security::write_private(
                    &self.dir.join("call-phones.json"),
                    &serde_json::to_vec(&bindings)?,
                )?;
                state.call_phones = bindings;
                json!({"state":"selected","address":address})
            }
            "mute" => {
                if !on_computer {
                    bail!("Use computer audio before muting the computer microphone");
                }
                let address = address
                    .as_deref()
                    .context("Choose the paired phone for calls")?;
                let muted = data["muted"].as_bool().unwrap();
                let result = self
                    .call_audio
                    .lock()
                    .await
                    .mute(&self.dir, device, muted)
                    .await?;
                backend.local_mute(address, muted);
                let mut state = self.state.lock().await;
                if let Some(d) = state.devices.get_mut(device) {
                    d.state.insert("call_audio".into(), result);
                }
                json!({"state":"submitted"})
            }
            _ => {
                timeout(
                    Duration::from_secs(8),
                    backend.command(
                        address
                            .as_deref()
                            .context("Choose the paired phone for calls")?,
                        action,
                        data,
                    ),
                )
                .await??
            }
        };
        drop(backend);
        self.publish_hfp().await;
        Ok(result)
    }
    async fn call_audio_operation(
        &self,
        device: &str,
        action: &str,
        data: &Value,
        operation: &str,
    ) -> Result<Value> {
        let mut router = self.call_audio.lock().await;
        if self
            .state
            .lock()
            .await
            .operations
            .get(operation)
            .is_none_or(|o| o.status != "running")
        {
            bail!("Call audio operation cancelled")
        }
        let d = self.device(device).await?;
        if !d.connected
            || d.disabled.iter().any(|f| f == "call_control")
            || !d
                .capabilities
                .iter()
                .any(|c| c.id == "call_control" && c.status == Availability::Available)
        {
            bail!("call control unavailable")
        }
        let value = match action {
            "audio_devices" => return call_audio::Router::devices().await,
            "audio_start" => {
                if d.state.get("call_control").is_some_and(|v| {
                    v["backend"] == "bluetooth_hfp" && v["address"] != data["address"]
                }) {
                    bail!("Use the selected call phone for computer audio");
                }
                if !d.state.get("call_control").is_some_and(|v| {
                    v["calls"]
                        .as_array()
                        .is_some_and(|calls| calls.iter().any(|c| c["state"] == "active"))
                        && v["route"] == "bluetooth"
                }) {
                    bail!(
                        "Select computer Bluetooth audio on the phone during an active call first"
                    )
                }
                router
                    .start(
                        &self.dir,
                        device,
                        data["address"].as_str().unwrap(),
                        data["output"].as_str().unwrap_or(""),
                        data["microphone"].as_str().unwrap_or(""),
                        d.state
                            .get("call_control")
                            .is_some_and(|v| v["muted"] == true),
                    )
                    .await?
            }
            "audio_select" => {
                router
                    .select(
                        &self.dir,
                        device,
                        data["output"].as_str().unwrap(),
                        data["microphone"].as_str().unwrap(),
                    )
                    .await?
            }
            "audio_stop" => router.stop(&self.dir, device).await?,
            "audio_mute" => {
                router
                    .mute(&self.dir, device, data["muted"].as_bool().unwrap())
                    .await?
            }
            _ => bail!("unsupported call audio action"),
        };
        let mut state = self.state.lock().await;
        if state
            .operations
            .get(operation)
            .is_none_or(|o| o.status != "running")
        {
            drop(state);
            if action == "audio_start" {
                router.stop(&self.dir, device).await?;
            }
            bail!("Call audio operation cancelled")
        }
        if let Some(d) = state
            .devices
            .get_mut(device)
            .filter(|d| d.connected && !d.disabled.iter().any(|f| f == "call_control"))
        {
            let current = d
                .state
                .entry("call_audio".into())
                .or_insert_with(|| json!({}));
            for (k, v) in value.as_object().unwrap() {
                current[k] = v.clone();
            }
        } else {
            drop(state);
            router.stop(&self.dir, device).await?;
            bail!("device disconnected during call audio setup")
        }
        drop(state);
        drop(router);
        if action == "audio_mute"
            && d.state
                .get("call_control")
                .is_some_and(|c| c["backend"] == "bluetooth_hfp")
        {
            if let Some(address) = d
                .state
                .get("call_control")
                .and_then(|c| c["address"].as_str())
            {
                self.call_hfp
                    .lock()
                    .await
                    .local_mute(address, data["muted"].as_bool().unwrap());
                self.publish_hfp().await;
            }
        }
        self.changed.send(device.into()).ok();
        Ok(value)
    }
    pub async fn devices(&self) -> Vec<Device> {
        self.state.lock().await.devices.values().cloned().collect()
    }
    pub async fn device(&self, id: &str) -> Result<Device> {
        self.state
            .lock()
            .await
            .devices
            .get(id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("unknown device"))
    }
    pub async fn pending_pairs(&self) -> Value {
        let s = self.state.lock().await;
        json!(s
            .pairs
            .iter()
            .filter(|(_, p)| p.expires > Instant::now())
            .map(|(id, p)| json!({"id":id,"name":p.name}))
            .collect::<Vec<_>>())
    }
    pub async fn discover(&self, seconds: u32) -> Result<()> {
        if seconds == 0 || seconds > 120 {
            bail!("discovery window must be 1..120 seconds")
        };
        self.state.lock().await.discovery_until =
            Some(Instant::now() + Duration::from_secs(seconds as u64));
        self.changed.send(String::new()).ok();
        Ok(())
    }
    pub async fn approve_pair(&self, id: &str, approve: bool) -> Result<()> {
        let p = self
            .state
            .lock()
            .await
            .pairs
            .remove(id)
            .ok_or_else(|| anyhow::anyhow!("pair request not found"))?;
        if p.expires < Instant::now() {
            bail!("pair request expired")
        };
        p.approve
            .send(approve)
            .map_err(|_| anyhow::anyhow!("pairing connection closed"))?;
        Ok(())
    }
    pub async fn unpair(&self, id: &str) -> Result<()> {
        self.stop_call_audio(id).await;
        let mut s = self.state.lock().await;
        let mut next = s.trust.clone();
        if next.devices.remove(id).is_none() {
            bail!("unknown trusted device")
        };
        next.save(&self.dir)?;
        s.trust = next;
        audio_session::cancel_device(&s, id);
        screen_session::cancel_device(&mut s, id);
        if let Some(sess) = s.sessions.remove(id) {
            for link in sess.0 {
                link.stop.send(true).ok();
                retire_generation(&mut s, id, &link.generation, &self.changed);
            }
        }
        s.devices.remove(id);
        self.changed.send(id.into()).ok();
        Ok(())
    }
    pub async fn disconnect(&self, id: &str) -> Result<()> {
        self.stop_call_audio(id).await;
        let mut s = self.state.lock().await;
        audio_session::cancel_device(&s, id);
        screen_session::cancel_device(&mut s, id);
        let session = s
            .sessions
            .get(id)
            .ok_or_else(|| anyhow::anyhow!("device disconnected"))?;
        for link in &session.0 {
            link.stop.send(true).ok();
        }
        Ok(())
    }
    pub async fn set_enabled(&self, id: &str, feature: &str, enabled: bool) -> Result<()> {
        if feature == "call_control" && !enabled {
            self.stop_call_audio(id).await;
        }
        if unity_plugins::plugin(feature).is_none() {
            bail!("unknown feature")
        };
        let mut s = self.state.lock().await;
        let d = s
            .devices
            .get_mut(id)
            .ok_or_else(|| anyhow::anyhow!("unknown device"))?;
        d.disabled.retain(|f| f != feature);
        if !enabled {
            d.disabled.push(feature.into());
            d.state.remove(feature);
            if feature == "sound" {
                d.state.remove("desktop_sound");
            }
            if feature == "media" {
                d.phone_artwork.clear();
                d.state.remove("desktop_media");
                d.state.remove("sound");
                d.state.remove("desktop_sound");
            }
        }
        if feature == "audio" && !enabled {
            audio_session::cancel_device(&s, id);
        }
        if feature == "screen" && !enabled {
            screen_session::cancel_device(&mut s, id);
        }
        if let Some(session) = s.sessions.get(id).filter(|_| {
            s.devices.get(id).is_some_and(|d| {
                d.capabilities
                    .iter()
                    .any(|c| matches!(c.id.as_str(), "lock" | "desktop_media" | "sound"))
            })
        }) {
            for link in &session.0 {
                link.tx
                    .try_send(Message::Capabilities {
                        capabilities: desktop_control::capabilities(&s.devices[id].disabled),
                    })
                    .ok();
            }
        }
        let values: BTreeMap<_, _> = s
            .devices
            .iter()
            .map(|(id, d)| (id.clone(), d.disabled.clone()))
            .collect();
        unity_security::write_private(
            &self.dir.join("features.json"),
            &serde_json::to_vec(&values)?,
        )?;
        self.changed.send(id.into()).ok();
        Ok(())
    }
    pub async fn operations(&self, owner: &str) -> Vec<Operation> {
        self.state
            .lock()
            .await
            .operations
            .values_mut()
            .filter(|o| o.owner == owner)
            .map(|o| {
                if o.status != "running" {
                    o.observed = true;
                }
                o.clone()
            })
            .collect()
    }
    pub async fn operation(&self, id: &str, owner: &str) -> Result<Operation> {
        let mut s = self.state.lock().await;
        let o = s
            .operations
            .get_mut(id)
            .ok_or_else(|| anyhow::anyhow!("unknown operation"))?;
        if o.owner != owner {
            bail!("operation belongs to another client")
        };
        if o.status != "running" {
            o.observed = true;
        }
        Ok(o.clone())
    }
    pub async fn cancel(&self, id: &str, owner: &str) -> Result<()> {
        let mut s = self.state.lock().await;
        let o = s
            .operations
            .get_mut(id)
            .ok_or_else(|| anyhow::anyhow!("unknown operation"))?;
        if o.owner != owner {
            bail!("operation belongs to another client")
        };
        if o.status == "running" {
            o.status = "cancelled".into();
            o.error =
                "Cancelled locally; remote actions already dispatched may have occurred".into();
        }
        if let Some(audio) = s.audio.as_ref().filter(|audio| audio.operation == id) {
            audio.stop.send(true).ok();
        }
        if let Some(device) = s
            .screens
            .iter()
            .find(|(_, screen)| screen.operation == id)
            .map(|(device, screen)| {
                screen.stop.send(true).ok();
                device.clone()
            })
        {
            s.screen_frames.remove(&device);
        }
        self.changed.send(format!("operation:{id}")).ok();
        Ok(())
    }
    pub async fn execute(
        self: &Arc<Self>,
        device: &str,
        feature: &str,
        action: &str,
        data: Value,
        owner: &str,
    ) -> Result<String> {
        let plugin =
            unity_plugins::plugin(feature).ok_or_else(|| anyhow::anyhow!("unknown feature"))?;
        plugin.command(action, &data)?;
        let mut s = self.state.lock().await;
        let d = s
            .devices
            .get(device)
            .ok_or_else(|| anyhow::anyhow!("unknown device"))?;
        if !d.connected {
            bail!("device disconnected")
        };
        if feature == "photos"
            && matches!(action, "get" | "copy")
            && d.disabled.iter().any(|f| f == "files")
        {
            bail!("Enable file transfers to download gallery items")
        }
        if d.disabled
            .iter()
            .any(|f| f == feature || matches!(feature, "desktop_media" | "sound") && f == "media")
        {
            bail!("feature disabled")
        };
        if !d
            .capabilities
            .iter()
            .any(|c| c.id == feature && unity_plugins::command_available(c))
        {
            bail!("feature unavailable or permission required")
        }
        let hfp = d
            .state
            .get("call_control")
            .is_some_and(|v| v["backend"] == "bluetooth_hfp");
        if feature == "call_control"
            && d.state
                .get("call_control")
                .is_some_and(|v| v["reconnecting"] == true)
            && !matches!(
                action,
                "refresh"
                    | "dial"
                    | "contacts"
                    | "history"
                    | "audio_devices"
                    | "audio_stop"
                    | "hfp_devices"
                    | "hfp_select"
            )
        {
            bail!("Bluetooth call controls are reconnecting; try again when connected")
        }
        // A stream's start request is completed once playback/capture begins,
        // but its operation remains useful for cancellation and failure handling.
        let streams: Vec<_> = s
            .audio
            .iter()
            .map(|a| a.operation.clone())
            .chain(s.screens.values().map(|screen| screen.operation.clone()))
            .collect();
        let expired: Vec<_> = s
            .operations
            .iter()
            .filter(|(_, o)| {
                o.status != "running"
                    && !streams.contains(&o.id)
                    && o.started
                        .is_some_and(|t| t.elapsed() > Duration::from_secs(600))
            })
            .map(|(id, _)| id.clone())
            .collect();
        for expired_id in expired {
            s.operations.remove(&expired_id);
            self.changed
                .send(format!("remove_operation:{expired_id}"))
                .ok();
        }
        if s.operations.len() >= 512 {
            // Gallery thumbnails can fill the history well before its TTL. A
            // result already read by its owner can be retired under pressure;
            // running work and unread results must remain available.
            let oldest = s
                .operations
                .iter()
                .filter(|(_, o)| o.status != "running" && o.observed && !streams.contains(&o.id))
                .min_by_key(|(_, o)| o.started)
                .map(|(id, _)| id.clone());
            if let Some(id) = oldest {
                s.operations.remove(&id);
                self.changed.send(format!("remove_operation:{id}")).ok();
            } else {
                bail!("operation limit reached; finish running work or read pending results before retrying")
            }
        }
        let session = if feature == "audio" {
            audio_session::select_session(&s, device, &data)?
        } else {
            s.sessions
                .get(device)
                .and_then(|links| links.select(unity_transport::workload(feature)))
                .context("device disconnected")?
        };
        let tx = session.tx.clone();
        let transport_kind = session.kind;
        let generation = session.generation.clone();
        let media_generation = generation.clone();
        let id = Uuid::new_v4().simple().to_string();
        let audio_work = if feature == "audio" {
            Some(audio_session::prepare(&mut s, device, action, &data, &id)?)
        } else {
            None
        };
        let screen_work = if feature == "screen" {
            Some(screen_session::prepare(&mut s, device, action, &data, &id)?)
        } else {
            None
        };
        s.operations.insert(
            id.clone(),
            Operation {
                id: id.clone(),
                device: device.into(),
                feature: feature.into(),
                action: action.into(),
                status: "running".into(),
                progress: 0,
                result: Value::Null,
                error: String::new(),
                owner: owner.into(),
                started: Some(Instant::now()),
                observed: false,
                generation,
            },
        );
        drop(s);
        let engine = self.clone();
        let op = id.clone();
        let f = feature.to_string();
        let a = action.to_string();
        let call_device = device.to_owned();
        tokio::spawn(async move {
            if f == "sound" && data["source"] == "desktop" {
                let result = engine
                    .sound
                    .control(&engine, &call_device, &media_generation, &a, &data, &op)
                    .await;
                match result {
                    Ok(value) => engine.finish(&op, true, value, String::new()).await,
                    Err(error) => {
                        engine
                            .finish(&op, false, Value::Null, error.to_string())
                            .await
                    }
                }
                return;
            }
            if f == "desktop_media" {
                let result = engine
                    .desktop_media
                    .control(&engine, &call_device, &media_generation, &a, &data, &op)
                    .await;
                match result {
                    Ok(value) => engine.finish(&op, true, value, String::new()).await,
                    Err(error) => {
                        engine
                            .finish(&op, false, Value::Null, error.to_string())
                            .await
                    }
                }
                return;
            }
            if f == "call_control"
                && (a.starts_with("hfp_")
                    || hfp
                        && !a.starts_with("audio_")
                        && !matches!(a.as_str(), "dial" | "contacts" | "history"))
            {
                let result = engine.hfp_operation(&call_device, &a, &data, &op).await;
                match result {
                    Ok(value) => engine.finish(&op, true, value, String::new()).await,
                    Err(error) => {
                        engine
                            .finish(&op, false, Value::Null, error.to_string())
                            .await
                    }
                }
                return;
            }
            if f == "call_control" && a.starts_with("audio_") {
                let result = engine
                    .call_audio_operation(&call_device, &a, &data, &op)
                    .await;
                match result {
                    Ok(data) => engine.finish(&op, true, data, String::new()).await,
                    Err(error) => {
                        engine
                            .finish(&op, false, Value::Null, error.to_string())
                            .await
                    }
                }
                return;
            }
            if let Some(work) = audio_work {
                engine.run_audio_operation(&op, &tx, work).await;
                return;
            }
            if let Some(work) = screen_work {
                engine.run_screen_operation(&op, &tx, work).await;
                return;
            }
            let send_result = if f == "files" && a == "send" {
                engine.send_file(&op, &tx, &data, transport_kind).await
            } else {
                timeout(
                    Duration::from_secs(10),
                    tx.send(Message::Command {
                        id: op.clone(),
                        feature: f.clone(),
                        action: if f == "photos" && a == "copy" {
                            "get".to_owned()
                        } else {
                            a.clone()
                        },
                        data: if f == "photos" && matches!(a.as_str(), "get" | "copy") {
                            let mut payload = data;
                            payload["transfer_id"] = json!(op);
                            payload
                        } else {
                            data
                        },
                    }),
                )
                .await
                .map_err(anyhow::Error::from)
                .and_then(|v| v.map_err(anyhow::Error::from))
            };
            if let Err(e) = send_result {
                engine.finish(&op, false, Value::Null, e.to_string()).await;
                return;
            }
            tokio::time::sleep(Duration::from_secs(60)).await;
            let running = engine
                .state
                .lock()
                .await
                .operations
                .get(&op)
                .is_some_and(|o| o.status == "running");
            if running {
                engine
                    .finish(&op, false, Value::Null, "Remote operation timed out".into())
                    .await;
            }
        });
        Ok(id)
    }
    async fn send_file(
        &self,
        id: &str,
        tx: &mpsc::Sender<Message>,
        data: &Value,
        kind: Kind,
    ) -> Result<()> {
        use std::{io::Seek, os::unix::fs::OpenOptionsExt};
        use tokio::io::AsyncReadExt;
        let path = PathBuf::from(data["path"].as_str().context("missing path")?);
        let prepared = tokio::task::spawn_blocking(move || -> Result<_> {
            let mut file = std::fs::OpenOptions::new()
                .read(true)
                .custom_flags(libc_flag_nofollow())
                .open(&path)?;
            let md = file.metadata()?;
            if !md.is_file() || md.len() > unity_plugins::transfer::MAX_FILE {
                bail!("source must be a regular file of at most 64 MiB")
            };
            let name = path
                .file_name()
                .and_then(|n| n.to_str())
                .context("invalid filename")?
                .to_string();
            unity_plugins::transfer::safe_name(&name)?;
            // Hash in bounded chunks; the same opened descriptor is rewound for transmission.
            let mut hash = sha256_stream(&mut file)?;
            file.rewind()?;
            Ok((file, name, md.len(), std::mem::take(&mut hash)))
        })
        .await??;
        let (file, name, total, digest) = prepared;
        let mut file = tokio::fs::File::from_std(file);
        let send = |action: &str, data: Value| {
            let message = Message::Command {
                id: id.into(),
                feature: "files".into(),
                action: action.into(),
                data,
            };
            let tx = tx.clone();
            async move {
                timeout(Duration::from_secs(10), tx.send(message)).await??;
                Ok::<_, anyhow::Error>(())
            }
        };
        send(
            "begin",
            json!({"id":id,"name":name,"size":total,"sha256":digest}),
        )
        .await?;
        // Keep Bluetooth chunks within one TLS record after base64 framing;
        // large multi-record writes can block its shared control connection.
        let mut buffer = vec![0; if kind == Kind::Bluetooth { 8192 } else { 49152 }];
        let mut sent = 0;
        loop {
            if self
                .state
                .lock()
                .await
                .operations
                .get(id)
                .is_none_or(|o| o.status != "running")
            {
                send("abort", json!({"id":id})).await.ok();
                return Ok(());
            }
            let n = file.read(&mut buffer).await?;
            if n == 0 {
                break;
            };
            send(
                "chunk",
                json!({"id":id,"data":STANDARD.encode(&buffer[..n])}),
            )
            .await?;
            sent += n as u64;
            let mut changed = false;
            if let Some(o) = self.state.lock().await.operations.get_mut(id) {
                let progress = (sent * 100).checked_div(total).unwrap_or(100);
                changed = progress != o.progress;
                o.progress = progress;
            }
            if changed {
                self.changed.send(format!("operation:{id}")).ok();
            }
        }
        send("end", json!({"id":id})).await?;
        Ok(())
    }
    async fn finish(&self, id: &str, ok: bool, data: Value, error: String) {
        let mut s = self.state.lock().await;
        if let Some(o) = s.operations.get_mut(id) {
            if o.status != "running" {
                return;
            };
            o.status = if ok { "completed" } else { "failed" }.into();
            o.result = data;
            o.error = error;
            o.progress = if ok { 100 } else { o.progress };
        }
        self.changed.send(format!("operation:{id}")).ok();
    }
    pub async fn serve(self: Arc<Self>, bind: &str) -> Result<()> {
        self.recover_display().await;
        if let Err(error) = self.call_audio.lock().await.recover(&self.dir).await {
            tracing::warn!(%error, "Call audio recovery pending");
        }
        // Restore any output left behind by an interrupted previous daemon.
        if let Err(error) = audio::Capture::recover(&self.dir).await {
            tracing::warn!(%error, "Could not restore previous audio route; retrying on next audio request");
        }
        let listener = TcpListener::bind((bind, self.port)).await?;
        self.start_extra_lan(bind).await?;
        self.receipts.start().await;
        let clipboard_engine = self.clone();
        let clipboard_stop = self.call_alerts_stop.subscribe();
        *self.clipboard_task.lock().await = Some(tokio::spawn(async move {
            clipboard_engine
                .clipboard
                .run(clipboard_engine.clone(), clipboard_stop)
                .await;
        }));
        let phone_media_engine = self.clone();
        let phone_media_stop = self.call_alerts_stop.subscribe();
        *self.phone_media_task.lock().await = Some(tokio::spawn(async move {
            if let Err(error) = phone_media::run(phone_media_engine, phone_media_stop).await {
                tracing::warn!(%error, "Phone system media controls unavailable");
            }
        }));
        let sound_engine = self.clone();
        let sound_stop = self.call_alerts_stop.subscribe();
        *self.sound_task.lock().await = Some(tokio::spawn(async move {
            if let Err(error) = sound_engine
                .sound
                .run(sound_engine.clone(), sound_stop)
                .await
            {
                tracing::warn!(%error,"Sound controls unavailable");
            }
        }));
        let media_engine = self.clone();
        let media_stop = self.call_alerts_stop.subscribe();
        *self.desktop_media_task.lock().await = Some(tokio::spawn(async move {
            if let Err(error) = media_engine
                .desktop_media
                .run(media_engine.clone(), media_stop)
                .await
            {
                tracing::warn!(%error,"Laptop media unavailable");
            }
        }));
        let phone_engine = self.clone();
        let phone_stop = self.call_alerts_stop.subscribe();
        *self.phone_alerts_task.lock().await = Some(tokio::spawn(async move {
            if let Err(error) = phone_engine
                .phone_alerts
                .run(phone_engine.clone(), phone_stop)
                .await
            {
                tracing::warn!(%error, "Phone desktop alerts unavailable");
            }
        }));
        let call_engine = self.clone();
        let call_alerts_stop = self.call_alerts_stop.subscribe();
        let call_task = tokio::spawn(async move {
            if let Err(error) = call_alerts::run(call_engine, call_alerts_stop).await {
                tracing::warn!(%error,"Native call alerts unavailable");
            }
        });
        *self.call_alerts_task.lock().await = Some(call_task);
        let engine = self.clone();
        let stop = self.call_alerts_stop.subscribe();
        *self.call_hfp_task.lock().await = Some(tokio::spawn(call_hfp::run(engine, stop)));
        let engine = self.clone();
        let mut stop = self.call_alerts_stop.subscribe();
        *self.call_audio_task.lock().await = Some(tokio::spawn(async move {
            let mut tick = interval(Duration::from_secs(1));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                tokio::select! {
                    _ = stop.changed() => break,
                    _ = tick.tick() => {
                        let mut router = engine.call_audio.lock().await;
                        if *stop.borrow() { break; }
                        if let Some((device, value)) = router.refresh(&engine.dir).await {
                            let mut state = engine.state.lock().await;
                            if let Some(d) = state.devices.get_mut(&device).filter(|d| d.connected
                                && !d.disabled.iter().any(|f| f == "call_control")) {
                                // Poll only while a route or deferred restoration exists;
                                // notify GTK only when routing actually changes.
                                if d.state.get("call_audio") != Some(&value) {
                                    d.state.insert("call_audio".into(), value);
                                    engine.changed.send(device).ok();
                                }
                            }
                        }
                    }
                }
            }
        }));
        let engine = self.clone();
        tokio::spawn(async move {
            engine.serve_bluetooth().await;
        });
        self.serve_lan_listener(listener).await
    }
    pub async fn bluetooth_info(&self) -> Value {
        self.bluetooth.lock().await.clone()
    }
    async fn serve_bluetooth(self: Arc<Self>) {
        loop {
            let result = async {
                let mut listener = unity_transport::bluetooth::Listener::new().await?;
                let mut tick = interval(Duration::from_secs(10));
                loop {
                    tokio::select! {
                        _ = tick.tick() => {
                            if !listener.healthy().await { bail!("Bluetooth service restarted") }
                            *self.bluetooth.lock().await = listener.info().await?;
                        },
                        incoming = listener.accept() => {
                            let incoming = incoming?;
                            let Ok(permit) = self.limit.clone().try_acquire_owned() else { continue };
                            let engine = self.clone();
                            tokio::spawn(async move {
                                let _permit = permit;
                                if let Err(e) = engine.accept_stream(Box::new(incoming.stream), unity_transport::Kind::Bluetooth, Some(incoming.channel), None).await {
                                    tracing::debug!(peer=%incoming.device,error=%e,"Bluetooth session ended");
                                }
                            });
                        }
                    }
                }
                #[allow(unreachable_code)] Ok::<(), anyhow::Error>(())
            }.await;
            let reason = result.err().map(|e| e.to_string()).unwrap_or_default();
            *self.bluetooth.lock().await =
                json!({"status":"unavailable","reason":reason,"adapters":[]});
            tracing::debug!(%reason,"Bluetooth unavailable; retrying");
            tokio::time::sleep(Duration::from_secs(10)).await;
        }
    }
    async fn accept_stream(
        self: &Arc<Self>,
        socket: Box<dyn unity_transport::StreamIo>,
        kind: unity_transport::Kind,
        channel: Option<unity_transport::bluetooth::Channel>,
        local_ip: Option<std::net::IpAddr>,
    ) -> Result<()> {
        use unity_transport::bluetooth::Channel;
        let acceptor = tokio_rustls_acceptor(self.identity.server_config()?);
        let mut tls = timeout(Duration::from_secs(20), acceptor.accept(socket)).await??;
        let hello = timeout(
            Duration::from_secs(10),
            unity_transport::read_frame(&mut tls),
        )
        .await??;
        match hello {
            Message::AudioHello {
                version,
                device_id,
                token,
            } if channel.is_none() || channel == Some(Channel::Audio) => {
                self.accept_audio(version, &device_id, &token, kind, tls)
                    .await
            }
            Message::ScreenHello {
                version,
                device_id,
                token,
            } if channel.is_none() || channel == Some(Channel::Screen) => {
                self.accept_screen(version, &device_id, &token, kind, tls)
                    .await
            }
            hello @ Message::Hello { .. }
                if channel.is_none() || channel == Some(Channel::Control) =>
            {
                let mut transport = if kind == unity_transport::Kind::Bluetooth {
                    LanTransport::bluetooth(tls)
                } else {
                    LanTransport::new(tls)
                };
                self.authenticate_and_run(&mut transport, hello, local_ip)
                    .await
            }
            _ => bail!("Unexpected hello for this transport channel"),
        }
    }
    async fn authenticate_and_run(
        self: &Arc<Self>,
        t: &mut dyn Transport,
        hello: Message,
        local_ip: Option<std::net::IpAddr>,
    ) -> Result<()> {
        let Message::Hello {
            version,
            device_id: id,
            name,
            capabilities,
            network_info,
        } = hello
        else {
            bail!("expected hello")
        };
        unity_protocol::validate_hello(version, &id, &name, &capabilities)?;
        let trusted = self.state.lock().await.trust.devices.get(&id).cloned();
        if let Some(trust) = trusted {
            let nonce = hex::encode(unity_security::random_bytes());
            t.send(&Message::Challenge {
                nonce: nonce.clone(),
            })
            .await?;
            let Message::Proof { mac } = timeout(Duration::from_secs(10), t.receive()).await??
            else {
                bail!("expected authentication proof")
            };
            unity_security::verify(&trust.secret, &id, &nonce, &mac)?;
        } else {
            let (rx, name_copy) = {
                let mut s = self.state.lock().await;
                if !s
                    .discovery_until
                    .is_some_and(|until| until > Instant::now())
                {
                    bail!("pairing is closed")
                };
                if s.pairs.len() >= 8 || s.pairs.contains_key(&id) {
                    bail!("pair request limit")
                };
                let (tx, rx) = oneshot::channel();
                s.pairs.insert(
                    id.clone(),
                    PendingPair {
                        name: name.clone(),
                        approve: tx,
                        expires: Instant::now() + Duration::from_secs(90),
                    },
                );
                (rx, name.clone())
            };
            self.changed.send(String::new()).ok();
            t.send(&Message::PairPending {
                device_id: id.clone(),
            })
            .await?;
            let approved = timeout(Duration::from_secs(90), rx).await;
            self.state.lock().await.pairs.remove(&id);
            if !matches!(approved, Ok(Ok(true))) {
                bail!("pairing rejected or expired")
            }
            let secret = hex::encode(unity_security::random_bytes());
            {
                let mut s = self.state.lock().await;
                let mut trust = s.trust.clone();
                trust.devices.insert(
                    id.clone(),
                    Trust {
                        name: name_copy.clone(),
                        secret: secret.clone(),
                    },
                );
                trust.save(&self.dir)?;
                s.trust = trust;
                s.devices.insert(
                    id.clone(),
                    Device {
                        id: id.clone(),
                        name: name_copy,
                        connected: false,
                        capabilities: unity_plugins::capabilities(&capabilities),
                        state: BTreeMap::new(),
                        phone_artwork: BTreeMap::new(),
                        disabled: vec![],
                        last_error: String::new(),
                    },
                );
            }
            self.changed.send(id.clone()).ok();
            t.send(&Message::PairResult { secret }).await?;
            return Ok(());
        }
        let (tx, mut rx) = mpsc::channel(16);
        let (stop, mut cancel) = watch::channel(false);
        let generation = Uuid::new_v4().to_string();
        {
            let mut s = self.state.lock().await;
            if !s.trust.devices.contains_key(&id) || !s.devices.contains_key(&id) {
                bail!("device revoked during authentication")
            }
            let kind = t.kind();
            let old = s.sessions.get_mut(&id).and_then(|links| {
                links
                    .0
                    .iter()
                    .position(|link| link.kind == kind)
                    .map(|index| links.0.remove(index))
            });
            if let Some(old) = old {
                old.stop.send(true).ok();
                retire_generation(&mut s, &id, &old.generation, &self.changed);
            }
            let links = s.sessions.entry(id.clone()).or_default();
            let first = links.0.is_empty();
            links.0.push(Session {
                kind,
                local_ip,
                network_info,
                biometric_auth: capabilities
                    .iter()
                    .any(|c| c.id == "authentication" && c.status == Availability::Available),
                generation: generation.clone(),
                started_at: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64,
                tx,
                stop,
            });
            let transport = links.status();
            let d = s.devices.get_mut(&id).context("device was revoked")?;
            d.connected = true;
            d.name = name;
            if first {
                d.state.clear();
                d.phone_artwork.clear();
            }
            d.state.insert("transport".into(), transport);
            d.last_error.clear();
            d.capabilities = unity_plugins::capabilities(&capabilities);
        }
        self.changed.send(id.clone()).ok();
        tracing::info!(device=%id,transport=?t.kind(),"Authenticated device connected");
        let result=async {
 t.send(&Message::Ready).await?;
 if capabilities.iter().any(|c| matches!(c.id.as_str(),"lock"|"desktop_media"|"sound")) { let s=self.state.lock().await; t.send(&Message::Capabilities { capabilities: desktop_control::capabilities(&s.devices[&id].disabled) }).await?; }
 if network_info && self.state.lock().await.devices.get(&id).is_some_and(|d| !d.disabled.iter().any(|f| f=="connectivity")) {
 t.send(&Message::Event {feature:"connectivity".into(),data:self.network_info().await}).await?;
 }
 let mut heartbeat=interval(Duration::from_secs(30));heartbeat.tick().await;
 let mut last_seen=Instant::now();let mut invalid_events=0u8;let mut gallery=gallery::Downloads::new(self.dir.join("clipboard-files"))?;let mut files=unity_plugins::transfer::Receiver::new(self.dir.join("received"))?;let mut gallery_files=HashMap::<String,PathBuf>::new();
 loop{tokio::select!{
 _=cancel.changed()=>{bail!("session cancelled")},
 _=heartbeat.tick()=>{gallery.prune();if last_seen.elapsed()>Duration::from_secs(90){bail!("peer heartbeat timed out")};timeout(Duration::from_secs(10),t.send(&Message::Ping)).await??;},
 outbound=rx.recv()=>{let Some(m)=outbound else{break};timeout(Duration::from_secs(10),t.send(&m)).await??;},
 incoming=t.receive()=>{let message=incoming?;last_seen=Instant::now();if self.state.lock().await.sessions.get(&id).and_then(|links| links.generation(&generation)).is_none(){bail!("session replaced")};match message{
 Message::AuthResponse{id:request,signature}=>{self.authentication_response(&id,&generation,&request,signature).await;},
 Message::Ping=>{t.send(&Message::Pong).await?},Message::Pong=>{},
 Message::Capabilities{capabilities}=>{unity_protocol::validate_hello(1,&id,"device",&capabilities)?;let mut s=self.state.lock().await;if let Some(link)=s.sessions.get_mut(&id).and_then(|links|links.0.iter_mut().find(|l|l.generation==generation)){link.biometric_auth=capabilities.iter().any(|c|c.id=="authentication" && c.status==Availability::Available);}
if let Some(d)=s.devices.get_mut(&id){d.capabilities=unity_plugins::capabilities(&capabilities);let allowed:Vec<_>=d.capabilities.iter().filter(|c|c.status==Availability::Available).map(|c|c.id.clone()).collect();d.state.retain(|f,_|f == "transport" || (f == "desktop_sound" && allowed.iter().any(|a| a=="sound")) || (f == "call_audio" && allowed.iter().any(|a| a=="call_control")) || allowed.contains(f));if !allowed.iter().any(|f| f=="audio"){audio_session::cancel_device(&s,&id);}
 if !allowed.iter().any(|f| f=="screen"){screen_session::cancel_device(&mut s,&id);}}drop(s);if !self.device(&id).await?.capabilities.iter().any(|c| c.id=="call_control" && c.status==Availability::Available) { self.stop_call_audio(&id).await; }self.changed.send(id.clone()).ok();},
 Message::Request { id: request_id, feature, action, data } => {
 if let Ok(permit) = self.request_limit.clone().try_acquire_owned() {
 let engine=self.clone(); let device=id.clone();let generation=generation.clone();
 tokio::spawn(async move { let _permit=permit; engine.phone_request(device,generation,request_id,feature,action,data).await; });
 } else { t.send(&Message::Result { id:request_id,ok:false,data:Value::Null,error:"Too many desktop requests".into() }).await?; }
 },
 Message::Event{feature,data}=>{
 // Laptop snapshots and foreign-origin media are display-only. Drop echoes
 // before cache/dispatch so they can never become phone-native sessions.
 if feature=="sound" && data["origin"]!="phone" || feature=="desktop_media" || feature=="media" && data.get("origin").is_some_and(|o| o!="phone") { continue; }
 let validation=unity_plugins::plugin(&feature).context("unknown event feature").and_then(|p|p.event(&data));
 if let Err(error)=validation {invalid_events=invalid_events.saturating_add(1);tracing::warn!(feature=%feature.chars().take(64).collect::<String>(),error=%error,"Rejected feature update; connection retained");if invalid_events>=8{bail!("Too many invalid feature updates")};continue;}
 let allowed={let s=self.state.lock().await;let d=s.devices.get(&id).context("revoked device")?;!d.disabled.contains(&feature)&&!(feature=="sound" && d.disabled.iter().any(|f|f=="media"))&&d.capabilities.iter().any(|c|c.id==feature&&c.status==Availability::Available)};
 if !allowed{continue};
 if feature=="clipboard" { self.clipboard.received(&id,&generation,data["text"].as_str().unwrap()); }
 if feature=="media" && data["kind"]=="artwork" && self.state.lock().await.devices.get(&id).is_none_or(|d| d.disabled.iter().any(|f| f=="media_artwork")) {continue;}
 if feature=="call_control" && data["backend"]=="bluetooth_hfp" {
 let mut state=self.state.lock().await;let d=state.devices.get_mut(&id).context("revoked device")?;
 if d.state.get("call_control").is_none_or(|v| v["backend"]!="bluetooth_hfp") { d.state.insert("call_control".into(),data); }
 drop(state);self.publish_hfp().await;continue;
 }
 if feature=="call_control" && (data["calls"].as_array().is_some_and(|c| c.is_empty()) || data["route"]!="bluetooth") { self.stop_call_audio(&id).await; }
 if feature=="files" {
 let clipboard_request={let state=self.state.lock().await;state.operations.get(data["id"].as_str().unwrap_or("")).is_some_and(|o|o.device==id&&o.generation==generation&&o.feature=="photos"&&o.action=="copy"&&o.status=="running")};
 if gallery.event(&data,clipboard_request)?{continue;}
 }
 let value=if feature=="files"{let chunk=data["phase"]=="chunk";let received=files.event(&data)?;if chunk{continue};if data["phase"]=="abort"{json!({"state":"cancelled","id":data["id"]})}else if let Some(mut path)=received{
 let transfer=data["id"].as_str().unwrap_or("");
 let state=self.state.lock().await;
 if state.operations.get(transfer).is_some_and(|o|o.device==id&&o.generation==generation&&o.feature=="photos"&&o.action=="get"&&o.status=="running") {
  path=gallery::original_name(&path,transfer);
  gallery_files.retain(|key,_|state.operations.get(key).is_some_and(|o|o.status=="running"));
  gallery_files.insert(transfer.to_owned(),path.clone());
 }
 json!({"received_path":path,"state":"completed"})}else{json!({"state":"receiving","id":data["id"]})}}else{data};
 let mut s=self.state.lock().await;let d=s.devices.get_mut(&id).context("revoked device")?;
 self.receipts.received(&d.name, &feature, &value);
 self.phone_alerts.received(&id, &generation, &feature, &value);
 if feature=="notifications"{let notifications=d.state.entry(feature.clone()).or_insert_with(||json!({})).as_object_mut().context("invalid notification state")?;let key=value["id"].as_str().context("missing notification ID")?.to_string();if value["removed"].as_bool().unwrap_or(false){notifications.remove(&key);}else{if notifications.len()>=200{notifications.clear()};notifications.insert(key,value);}}else if feature=="sms" {let previous=d.state.entry(feature).or_insert_with(||json!({}));previous["revision"]=value["revision"].clone();if let Some(message)=value["message_id"].as_str() {if !previous["reports"].is_object(){previous["reports"]=json!({});}let reports=previous["reports"].as_object_mut().unwrap();if reports.len()>=100&&!reports.contains_key(message){reports.clear();}reports.insert(message.into(),value);}}else if feature=="media"{artwork::phone_event(d,value);}else{d.state.insert(feature,value);}
 drop(s);self.changed.send(id.clone()).ok();
 },
 Message::Result{id:op,ok,data,error}=>{self.clipboard.result(&id,&generation,&op,ok);if error.len()>512||serde_json::to_vec(&data)?.len()>unity_protocol::MAX_TEXT{bail!("operation response too large")};if !ok && self.phone_audio_failed(&id,&generation,&op,&error).await {continue;}let operation=self.state.lock().await.operations.get(&op).filter(|o|o.device==id&&o.generation==generation&&o.status=="running").cloned();if let Some(operation)=operation{
if operation.feature=="photos" && operation.action=="get" && ok {
 let file=gallery_files.remove(&op).filter(|_|data["transfer_id"].as_str()==Some(op.as_str()));
 if let Some(path)=file {let mut data=data;data["received_path"]=json!(path);self.finish(&op,true,data,String::new()).await;}
 else {self.finish(&op,false,Value::Null,"Gallery download was not verified; update the phone companion and retry".into()).await;}
 continue;
}
if operation.feature=="photos" && operation.action=="copy" {
 if !ok { self.finish(&op,false,data,error).await;continue; }
 let file=gallery.take(&op).and_then(|file|{if data["transfer_id"].as_str()==Some(op.as_str()){Ok(file)}else{Err(anyhow::anyhow!("Gallery transfer did not match its clipboard request"))}});
 let engine=self.clone();let device=id.clone();let generation=generation.clone();
 tokio::spawn(async move {
 let result=async {let file=file?;{let state=engine.state.lock().await;
 if !state.operations.get(&op).is_some_and(|o|o.status=="running") || !state.devices.get(&device).is_some_and(|d|d.connected&&!d.disabled.iter().any(|f|f=="photos"||f=="files")) || state.sessions.get(&device).and_then(|s|s.generation(&generation)).is_none(){bail!("Gallery permission or connection changed")}}
 clipboard::copy_file(file).await?;Ok::<_,anyhow::Error>(json!({"state":"copied","temporary":true}))}.await;
 match result {Ok(value)=>engine.finish(&op,true,value,String::new()).await,Err(error)=>engine.finish(&op,false,Value::Null,error.to_string()).await}
 });continue;
}
if operation.feature=="audio" || operation.feature=="screen" {if !ok {let mut state=self.state.lock().await; if operation.feature=="audio" {audio_session::cancel_device(&state,&id);}else{screen_session::cancel_device(&mut state,&id);}drop(state);self.finish(&op,false,data,error).await;}continue;}
if ok{unity_plugins::previews::validate_result(&operation.feature,&operation.action,&data)?;if operation.feature=="call_control" { unity_plugins::call_control::validate_result(&operation.action,&data)?; }}self.finish(&op,ok,data,error).await}},
 _=>bail!("unexpected session message")
 }}
 }}Ok::<_,anyhow::Error>(())
 }.await;
        self.authentication_disconnected(&generation).await;
        if let Err(error) = &result {
            tracing::info!(device=%id,transport=?t.kind(),%error,"Device connection ended");
        }
        if !self.device(&id).await?.connected
            || self
                .state
                .lock()
                .await
                .sessions
                .get(&id)
                .is_none_or(|links| links.0.len() == 1)
        {
            self.stop_call_audio(&id).await;
        }
        let mut s = self.state.lock().await;
        if s.sessions
            .get(&id)
            .is_some_and(|links| links.0.iter().any(|link| link.generation == generation))
        {
            retire_generation(&mut s, &id, &generation, &self.changed);
            let links = s.sessions.get_mut(&id).unwrap();
            links.0.retain(|link| link.generation != generation);
            let connected = links.select(Workload::Control).is_some();
            let transport = links.status();
            if links.0.is_empty() {
                s.sessions.remove(&id);
            }
            if let Some(d) = s.devices.get_mut(&id) {
                d.connected = connected;
                if connected {
                    d.state.insert("transport".into(), transport);
                } else {
                    d.state.clear();
                    d.phone_artwork.clear();
                }
                d.last_error = if connected {
                    String::new()
                } else {
                    result
                        .as_ref()
                        .err()
                        .map(|e| e.to_string())
                        .unwrap_or_default()
                };
            }
            self.changed.send(id).ok();
        }
        result
    }
}
fn retire_generation(
    state: &mut State,
    device: &str,
    generation: &str,
    changed: &broadcast::Sender<String>,
) {
    audio_session::cancel_generation(state, device, generation);
    screen_session::cancel_generation(state, device, generation);
    for operation in state
        .operations
        .values_mut()
        .filter(|op| op.device == device && op.generation == generation && op.status == "running")
    {
        operation.status = "failed".into();
        operation.error = "The connection used by this operation disconnected; retry to use the remaining connection".into();
        changed.send(format!("operation:{}", operation.id)).ok();
    }
}
fn libc_flag_nofollow() -> i32 {
    libc::O_NOFOLLOW
}
fn sha256_stream(file: &mut std::fs::File) -> Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut h = Sha256::new();
    let mut b = [0; 65536];
    loop {
        let n = file.read(&mut b)?;
        if n == 0 {
            break;
        };
        h.update(&b[..n])
    }
    Ok(format!("{:x}", h.finalize()))
}
fn tokio_rustls_acceptor(config: Arc<rustls::ServerConfig>) -> tokio_rustls::TlsAcceptor {
    tokio_rustls::TlsAcceptor::from(config)
}
