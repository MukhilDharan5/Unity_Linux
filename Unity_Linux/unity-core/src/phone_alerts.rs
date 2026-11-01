//! Phone notification/SMS popups are daemon-owned, even while GTK is closed.
use crate::Engine;
use anyhow::Result;
use futures_lite::StreamExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    sync::{mpsc, watch, Mutex},
    time::timeout,
};
use zbus::zvariant::Value as Variant;

const BUS: &str = "org.freedesktop.Notifications";
type Key = (String, String);
struct Event {
    device: String,
    generation: String,
    feature: String,
    data: Value,
}
pub(super) struct Notifications {
    tx: mpsc::Sender<Event>,
    rx: Mutex<Option<mpsc::Receiver<Event>>>,
}
impl Notifications {
    pub fn new() -> Self {
        let (tx, rx) = mpsc::channel(128);
        Self {
            tx,
            rx: Mutex::new(Some(rx)),
        }
    }
    pub fn received(&self, device: &str, generation: &str, feature: &str, data: &Value) {
        if feature != "notifications" && !(feature == "sms" && data.get("incoming").is_some()) {
            return;
        }
        if self
            .tx
            .try_send(Event {
                device: device.into(),
                generation: generation.into(),
                feature: feature.into(),
                data: data.clone(),
            })
            .is_err()
        {
            tracing::warn!("Phone notification queue full or unavailable");
        }
    }
    pub async fn run(&self, engine: Arc<Engine>, mut stop: watch::Receiver<bool>) -> Result<()> {
        let Some(mut rx) = self.rx.lock().await.take() else {
            return Ok(());
        };
        let bus = zbus::Connection::session().await?;
        let proxy = zbus::Proxy::new(&bus, BUS, "/org/freedesktop/Notifications", BUS).await?;
        let mut signals = proxy.receive_all_signals().await?;
        let dbus = zbus::fdo::DBusProxy::new(&bus).await?;
        let mut owners = dbus.receive_name_owner_changed().await?;
        let mut changes = engine.changed.subscribe();
        let mut active: HashMap<Key, Active> = HashMap::new();
        let mut seen: HashMap<Key, ([u8; 32], bool, Instant)> = HashMap::new();
        let mut owner = String::new();
        let mut activation_tokens = HashMap::<u32, String>::new();
        loop {
            tokio::select! {
                _ = stop.changed() => break,
                event = rx.recv() => {
                    let Some(event) = event else { break };
                    if !eligible(&engine, &event.device, &event.feature, Some(&event.generation)).await { continue; }
                    if event.feature == "notifications" && event.data["removed"] == true {
                        let source = (event.feature, event.data["id"].as_str().unwrap_or("").to_owned());
                        let removed: Vec<_> = active.iter().filter(|(key,item)| key.0 == event.device && item.sources.contains(&source)).map(|(key,_)| key.clone()).collect();
                        for key in removed { if let Some(item) = active.remove(&key) { close(&proxy,item.id).await; } }
                        continue;
                    }
                    let Ok(device) = engine.device(&event.device).await else { continue };
                    let incoming = if event.feature == "sms" { &event.data["incoming"] } else { &event.data };
                    let remote_id = incoming["id"].as_str().unwrap_or("");
                    if event.feature == "notifications" && device.state.get("notifications").and_then(|s| s.get(remote_id)).is_none() { continue; }
                    let sms_id = if event.feature == "sms" { Some(remote_id) } else { incoming["sms_id"].as_str() };
                    let key = (event.device.clone(), if let Some(id) = sms_id { format!("sms:{id}") } else { format!("notification:{remote_id}") });
                    let (title, text) = if event.feature == "sms" {
                        (format!("SMS · {}", incoming["address"].as_str().filter(|s| !s.is_empty()).unwrap_or("Unknown sender")), incoming["body"].as_str().unwrap_or(""))
                    } else {
                        let app = incoming["app_name"].as_str().filter(|s| !s.is_empty()).or_else(|| incoming["app"].as_str()).unwrap_or("Phone notification");
                        let title = incoming["title"].as_str().unwrap_or("");
                        (if title.is_empty() { app.to_owned() } else { format!("{app} · {title}") }, incoming["text"].as_str().unwrap_or(""))
                    };
                    let digest: [u8;32] = Sha256::digest(serde_json::to_vec(&serde_json::json!([title,text,incoming["timestamp"]]))?).into();
                    let duplicate = seen.get(&key).is_some_and(|(old,alerted,_)| *alerted && *old == digest);
                    let previously_seen = seen.get(&key).is_some_and(|(_,alerted,_)| *alerted);
                    let source = (event.feature.clone(), remote_id.to_owned());
                    if let Some(item) = active.get_mut(&key) { item.sources.insert(source.clone()); }
                    // An inbox row is one message even if its SMS-app mirror arrives later.
                    let prefer_mirror = event.feature == "sms" && active.get(&key).is_some_and(|item| item.sources.iter().any(|(feature,_)| feature == "notifications"));
                    if duplicate || prefer_mirror || (sms_id.is_some() && previously_seen && !active.contains_key(&key)) { continue; }
                    while seen.len() >= 512 && !seen.contains_key(&key) {
                        if let Some(old) = seen.iter().min_by_key(|(_,(_,_,time))| *time).map(|(key,_)| key.clone()) { seen.remove(&old); }
                    }
                    seen.insert(key.clone(),(digest,previously_seen,Instant::now()));
                    let alert = if event.feature == "sms" { true } else if let Some(alert) = incoming["alert"].as_bool() { alert } else {
                        // Older companions do not identify list snapshots. Their
                        // pre-connection timestamps must not create a popup storm.
                        let state = engine.state.lock().await;
                        let start = state.sessions.get(&event.device).and_then(|s| s.0.iter().find(|s| s.generation == event.generation)).map(|s| s.started_at).unwrap_or(u64::MAX);
                        incoming["timestamp"].as_u64().is_some_and(|time| time >= start.saturating_sub(5000))
                    };
                    // List/reconnect snapshots update the app cache without resurfacing old alerts.
                    if !alert && !active.contains_key(&key) { continue; }
                    let caps: Vec<String> = match timeout(Duration::from_secs(3),proxy.call("GetCapabilities",&())).await { Ok(Ok(caps)) => caps, _ => continue };
                    let current = dbus.get_name_owner(BUS.try_into()?).await.ok().map(|v| v.to_string()).unwrap_or_default();
                    if current != owner { active.clear(); activation_tokens.clear(); owner = current; }
                    if !eligible(&engine,&event.device,&event.feature,Some(&event.generation)).await { continue; }
                    let replace = active.get(&key).map(|v| v.id).unwrap_or(0);
                    if !alert && replace == 0 { continue; }
                    let body = format!("{}\n{}",device.name,text.chars().take(1024).collect::<String>());
                    let body = if caps.iter().any(|c| c == "body-markup") { escape(&body) } else { body };
                    let actions = if caps.iter().any(|c| c == "actions") { vec!["default","Open in Unity","open","Open in Unity"] } else { vec![] };
                    let hints = HashMap::from([("desktop-entry",Variant::from("org.unityconnect.Unity")),("category",Variant::from("im.received")),("urgency",Variant::from(1u8)),("suppress-sound",Variant::from(replace != 0 || !alert))]);
                    let result = timeout(Duration::from_secs(3),proxy.call::<_,_,u32>("Notify", &("Unity",replace,"org.unityconnect.Unity",title,body,actions,hints,-1i32))).await;
                    if let Ok(Ok(id)) = result {
                        seen.insert(key.clone(),(digest,true,Instant::now()));
                        while active.len() >= 128 && !active.contains_key(&key) {
                            if let Some(old) = active.iter().min_by_key(|(_,v)| v.created).map(|(key,_)| key.clone()) { if let Some(item) = active.remove(&old) { close(&proxy,item.id).await; } }
                        }
                        let mut sources = active.remove(&key).map(|v| v.sources).unwrap_or_default(); sources.insert(source);
                        active.insert(key,Active { id,sources,created:Instant::now(),messages:sms_id.is_some() });
                    }
                }
                event = signals.next() => {
                    let Some(event) = event else { break };
                    let current = dbus.get_name_owner(BUS.try_into()?).await.ok().map(|v| v.to_string()).unwrap_or_default();
                    if current != owner { active.clear(); activation_tokens.clear(); owner = current; }
                    if event.header().sender().is_none_or(|s| s.as_str() != owner) { continue; }
                    match event.header().member().map(|s| s.as_str()) {
                        Some("ActivationToken") => {
                            let (id,token): (u32,String) = event.body().deserialize()?;
                            if token.len() <= 4096 && active.values().any(|item| item.id == id) { activation_tokens.insert(id,token); }
                        }
                        Some("NotificationClosed") => { let (id,_): (u32,u32) = event.body().deserialize()?; active.retain(|_,v| v.id != id); activation_tokens.remove(&id); }
                        Some("ActionInvoked") => {
                            let (id,action): (u32,String) = event.body().deserialize()?;
                            if matches!(action.as_str(),"default"|"open") {
                                if let Some((key,item)) = active.iter().find(|(_,v)| v.id == id) {
                                    let view = if item.messages && eligible(&engine,&key.0,"sms",None).await { "--open-messages" } else { "--open-notifications" };
                                    let activation = activation_tokens.remove(&id).unwrap_or_default();
                                    crate::call_alerts::launch_client_with_activation(&[view,&key.0], &activation).await;
                                }
                            }
                        }
                        _ => ()
                    }
                }
                changed = changes.recv() => {
                    if matches!(changed,Err(tokio::sync::broadcast::error::RecvError::Closed)) { break; }
                    let state = engine.state.lock().await;
                    let mut removed = Vec::new();
                    for (key,item) in &active {
                        if item.sources.iter().any(|(feature,remote)| !state.devices.get(&key.0).is_some_and(|d| available(d,feature) && (feature != "notifications" || d.state.get("notifications").and_then(|s| s.get(remote)).is_some()))) {
                            removed.push(key.clone());
                        }
                    }
                    drop(state);
                    for key in removed { if let Some(item) = active.remove(&key) { close(&proxy,item.id).await; } }
                }
                event = owners.next() => {
                    let Some(event) = event else { break };
                    if event.args()?.name().as_str() == BUS {
                        let current = dbus.get_name_owner(BUS.try_into()?).await.ok().map(|v| v.to_string()).unwrap_or_default();
                        if current != owner { active.clear(); activation_tokens.clear(); owner = current; }
                    }
                }
            }
            activation_tokens.retain(|id, _| active.values().any(|item| item.id == *id));
        }
        for item in active.values() {
            close(&proxy, item.id).await;
        }
        Ok(())
    }
}
struct Active {
    id: u32,
    sources: HashSet<(String, String)>,
    created: Instant,
    messages: bool,
}
fn available(device: &crate::Device, feature: &str) -> bool {
    device.connected
        && !device.disabled.iter().any(|f| f == feature)
        && device
            .capabilities
            .iter()
            .any(|c| c.id == feature && c.status == unity_protocol::Availability::Available)
}
async fn eligible(engine: &Engine, device: &str, feature: &str, generation: Option<&str>) -> bool {
    let state = engine.state.lock().await;
    state
        .devices
        .get(device)
        .is_some_and(|d| available(d, feature))
        && generation.is_none_or(|generation| {
            state.sessions.get(device).is_some_and(|s| {
                s.0.iter()
                    .any(|s| s.generation == generation && !*s.stop.borrow())
            })
        })
}
async fn close(proxy: &zbus::Proxy<'_>, id: u32) {
    timeout(
        Duration::from_secs(1),
        proxy.call::<_, _, ()>("CloseNotification", &(id,)),
    )
    .await
    .ok();
}
fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}
