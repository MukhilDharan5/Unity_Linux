//! Native laptop MPRIS discovery. Exclude remote phone exports to prevent media loops.
use super::Engine;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    sync::{watch, Mutex},
    time::{interval, timeout},
};
use unity_protocol::{Availability, Message};
use unity_transport::Workload;
use zbus::zvariant::{OwnedObjectPath, OwnedValue};
const PATH: &str = "/org/mpris/MediaPlayer2";
const PLAYER: &str = "org.mpris.MediaPlayer2.Player";
type Properties = HashMap<String, OwnedValue>;
#[derive(Clone)]
struct Player {
    name: String,
    owner: String,
    value: Value,
    art_uri: String,
    sampled: Instant,
    reported_position: i64,
}
#[derive(Default)]
pub(super) struct Backend {
    players: Mutex<Vec<Player>>,
    artwork: Arc<super::artwork::Cache>,
    // Serialize commands and remember completed requests. Replayed next/previous
    // requests must never skip another track, including across simultaneous links.
    commands: Mutex<VecDeque<(String, String, Instant)>>,
}
fn bounded_text(value: &str) -> String {
    let mut end = value.len().min(512);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].replace('\0', "")
}
fn text(props: &Properties, key: &str) -> String {
    bounded_text(
        props
            .get(key)
            .and_then(|v| <&str>::try_from(v).ok())
            .unwrap_or(""),
    )
}
fn number(props: &Properties, key: &str) -> i64 {
    props
        .get(key)
        .and_then(|v| {
            i64::try_from(v)
                .ok()
                .or_else(|| u64::try_from(v).ok().and_then(|n| i64::try_from(n).ok()))
        })
        .unwrap_or(0)
}
fn flag(props: &Properties, key: &str) -> bool {
    props
        .get(key)
        .and_then(|v| bool::try_from(v).ok())
        .unwrap_or(false)
}
fn native(name: &str) -> bool {
    let suffix = name
        .strip_prefix("org.mpris.MediaPlayer2.")
        .unwrap_or("")
        .to_ascii_lowercase();
    !suffix.is_empty()
        && ![
            "unity",
            "unityconnect",
            "kdeconnect",
            "gsconnect",
            "mpris-proxy",
        ]
        .iter()
        .any(|p| suffix == *p || suffix.starts_with(&format!("{p}.")))
}
async fn properties(bus: &zbus::Connection, owner: &str, interface: &str) -> Result<Properties> {
    let proxy = zbus::Proxy::new(bus, owner, PATH, "org.freedesktop.DBus.Properties").await?;
    Ok(timeout(Duration::from_secs(2), proxy.call("GetAll", &(interface,))).await??)
}
async fn inspect(
    bus: &zbus::Connection,
    name: String,
    previous: Option<Player>,
    artwork: Arc<super::artwork::Cache>,
) -> Result<Player> {
    let dbus = zbus::fdo::DBusProxy::new(bus).await?;
    let owner = dbus
        .get_name_owner(name.as_str().try_into()?)
        .await?
        .to_string();
    let previous = previous.filter(|p| p.owner == owner);
    let props = match properties(bus, &owner, PLAYER).await {
        Ok(value) => value,
        Err(error) => {
            return previous
                .filter(|p| p.sampled.elapsed() < Duration::from_secs(3))
                .ok_or(error)
        }
    };
    let metadata: Properties = match props
        .get("Metadata")
        .and_then(|v| v.try_clone().ok())
        .and_then(|v| v.try_into().ok())
    {
        Some(value) => value,
        None => {
            return previous
                .filter(|p| p.sampled.elapsed() < Duration::from_secs(3))
                .context("Player metadata is temporarily unavailable")
        }
    };
    let root = properties(bus, &owner, "org.mpris.MediaPlayer2")
        .await
        .unwrap_or_default();
    let desktop_entry = text(&root, "DesktopEntry").to_ascii_lowercase();
    if desktop_entry.contains("unityconnect")
        || desktop_entry.contains("kdeconnect")
        || desktop_entry.contains("gsconnect")
    {
        bail!("Remote proxy is not native media")
    }
    let artists: Vec<String> = metadata
        .get("xesam:artist")
        .and_then(|v| v.try_clone().ok())
        .and_then(|v| v.try_into().ok())
        .unwrap_or_default();
    let mut duration = (number(&metadata, "mpris:length") / 1000).clamp(0, 86_400_000);
    let track = metadata
        .get("mpris:trackid")
        .and_then(|v| OwnedObjectPath::try_from(v.try_clone().ok()?).ok())
        .map(|p| p.to_string())
        .filter(|p| p.len() <= 1024)
        .unwrap_or_default();
    let title = text(&metadata, "xesam:title");
    let artist = bounded_text(&artists.join(", "));
    let same_track = previous.as_ref().is_some_and(|p| {
        !track.is_empty()
            && track != "/org/mpris/MediaPlayer2/TrackList/NoTrack"
            && p.value["track_id"] == track
            || track.is_empty()
                && !title.is_empty()
                && p.value["title"] == title
                && p.value["artist"] == artist
    });
    if duration == 0 && same_track {
        duration = previous
            .as_ref()
            .and_then(|p| p.value["duration"].as_i64())
            .unwrap_or(0);
    }
    let playing = text(&props, "PlaybackStatus") == "Playing";
    let speed = props
        .get("Rate")
        .and_then(|v| f64::try_from(v).ok())
        .unwrap_or(1.0)
        .clamp(0.0, 4.0);
    // Position does not emit PropertiesChanged. Some players omit it from
    // GetAll; query the live property without waiting for a pause transition.
    let position_proxy =
        zbus::Proxy::new(bus, &*owner, PATH, "org.freedesktop.DBus.Properties").await?;
    let fresh = timeout(
        Duration::from_millis(500),
        position_proxy.call::<_, _, OwnedValue>("Get", &(PLAYER, "Position")),
    )
    .await;
    let reported_position = match fresh {
        Ok(Ok(value)) => i64::try_from(&value).unwrap_or_else(|_| number(&props, "Position")),
        _ => number(&props, "Position"),
    };
    let mut position = reported_position / 1000;
    if let Some(old) = previous.as_ref().filter(|p| {
        same_track && p.reported_position == reported_position && p.value["playing"] == true
    }) {
        position = old.value["position"].as_i64().unwrap_or(0)
            + (old.sampled.elapsed().as_secs_f64() * 1000.0 * speed) as i64;
    }
    position = position.clamp(0, if duration > 0 { duration } else { 86_400_000 });
    let mut art_uri = metadata
        .get("mpris:artUrl")
        .and_then(|v| <&str>::try_from(v).ok())
        .filter(|uri| uri.len() <= 8192)
        .unwrap_or("")
        .to_owned();
    if art_uri.is_empty() && same_track {
        art_uri = previous
            .as_ref()
            .map(|p| p.art_uri.clone())
            .unwrap_or_default();
    }
    let mut actions = Vec::new();
    if flag(&props, "CanControl") {
        for (action, capability) in [
            ("play", "CanPlay"),
            ("pause", "CanPause"),
            ("next", "CanGoNext"),
            ("previous", "CanGoPrevious"),
            ("seek", "CanSeek"),
        ] {
            if flag(&props, capability) && (action != "seek" || duration > 0 && !track.is_empty()) {
                actions.push(action);
            }
        }
    }
    let session = hex::encode(Sha256::digest(format!("{name}\0{owner}")))[..32].to_owned();
    let image = artwork
        .image(&art_uri, &format!("{session}:{track}:{title}:{artist}"))
        .await;
    let image = image.or_else(|| {
        previous
            .as_ref()
            .filter(|_| same_track)
            .and_then(|p| p.value.get("artwork").filter(|v| !v.is_null()).cloned())
    });
    let value = json!({"origin":"desktop","session":session,"track_id":track,"app_name":text(&root,"Identity"),"package":name,
        "title":title,"artist":artist,"album":text(&metadata,"xesam:album"),
        "playing":playing,"active":true,"duration":duration,"speed":speed,
        "position":position,"actions":actions,"artwork_id":image.as_ref().and_then(|i|i["id"].as_str()),"artwork":image});
    Ok(Player {
        name,
        owner,
        value,
        art_uri,
        sampled: Instant::now(),
        reported_position,
    })
}
fn allowed(device: &super::Device) -> bool {
    device.connected
        && !device
            .disabled
            .iter()
            .any(|f| matches!(f.as_str(), "media" | "desktop_media"))
        && device
            .capabilities
            .iter()
            .any(|c| c.id == "desktop_media" && c.status == Availability::Available)
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
        bail!("Media permission or connection changed before dispatch")
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
        let fingerprint = serde_json::to_string(&json!([action, data]))?;
        let mut commands = self.commands.lock().await;
        authorize(engine, device, generation, request).await?;
        commands.retain(|(_, _, time)| time.elapsed() < Duration::from_secs(600));
        if let Some((_, old, _)) = commands.iter().find(|(id, _, _)| id == request) {
            if old != &fingerprint {
                bail!("Replayed media request changed its contents")
            }
            return Ok(json!({"state":"duplicate_ignored"}));
        }
        // Claim before dispatch: even a timeout must not cause an automatic retry
        // of a command the player might already have executed.
        if commands.len() >= 256 {
            commands.pop_front();
        }
        commands.push_back((request.to_owned(), fingerprint, Instant::now()));
        if action == "refresh" {
            return Ok(json!({"state":"refreshed"}));
        }
        let session = data["session"].as_str().context("Choose a laptop player")?;
        let player = self
            .players
            .lock()
            .await
            .iter()
            .find(|p| p.value["session"] == session)
            .cloned()
            .context("The selected laptop player ended; choose a current player")?;
        let bus = zbus::Connection::session().await?;
        let dbus = zbus::fdo::DBusProxy::new(&bus).await?;
        if dbus
            .get_name_owner(player.name.as_str().try_into()?)
            .await?
            .as_str()
            != player.owner
        {
            bail!("The selected laptop player restarted")
        }
        let props = properties(&bus, &player.owner, PLAYER).await?;
        let required = match action {
            "play" => "CanPlay",
            "pause" => "CanPause",
            "next" => "CanGoNext",
            "previous" => "CanGoPrevious",
            "seek" => "CanSeek",
            _ => bail!("Unsupported media control"),
        };
        if !flag(&props, "CanControl") || !flag(&props, required) {
            bail!("Player does not support this control")
        }
        let proxy = zbus::Proxy::new(&bus, player.owner.as_str(), PATH, PLAYER).await?;
        // Permissions may change while waiting for another command or D-Bus.
        authorize(engine, device, generation, request).await?;
        if action == "seek" {
            let metadata: Properties = props
                .get("Metadata")
                .context("missing metadata")?
                .try_clone()?
                .try_into()?;
            let track: OwnedObjectPath = metadata
                .get("mpris:trackid")
                .context("missing track")?
                .try_clone()?
                .try_into()?;
            if data["track_id"].as_str() != Some(track.as_str()) {
                bail!("Track changed before the seek; try again")
            }
            let duration = (number(&metadata, "mpris:length") / 1000).clamp(0, 86_400_000);
            let position = data["position"].as_u64().context("invalid seek position")?;
            if duration == 0 || position > duration as u64 {
                bail!("Seek is outside the current track")
            }
            timeout(
                Duration::from_secs(3),
                proxy.call::<_, _, ()>("SetPosition", &(track, position as i64 * 1000)),
            )
            .await??;
        } else {
            let method = match action {
                "play" => "Play",
                "pause" => "Pause",
                "next" => "Next",
                _ => "Previous",
            };
            timeout(Duration::from_secs(3), proxy.call::<_, _, ()>(method, &())).await??;
        }
        Ok(json!({"state":"submitted_to_laptop_player"}))
    }
    pub async fn run(&self, engine: Arc<Engine>, mut stop: watch::Receiver<bool>) -> Result<()> {
        let bus = zbus::Connection::session().await?;
        let dbus = zbus::fdo::DBusProxy::new(&bus).await?;
        let mut ticks = interval(Duration::from_secs(1));
        let mut sent = HashMap::<(String, String), Value>::new();
        let mut art_sent = HashMap::<(String, String, String), String>::new();
        loop {
            tokio::select! { _ = stop.changed() => break, _ = ticks.tick() => {} }
            if *stop.borrow() {
                break;
            }
            if !engine.state.lock().await.devices.values().any(allowed) {
                self.players.lock().await.clear();
                sent.clear();
                art_sent.clear();
                continue;
            }
            let names = timeout(Duration::from_secs(2), dbus.list_names()).await??;
            let previous = self.players.lock().await.clone();
            let mut tasks = tokio::task::JoinSet::new();
            for name in names
                .into_iter()
                .map(|n| n.to_string())
                .filter(|n| native(n))
                .take(16)
            {
                let bus = bus.clone();
                let old = previous.iter().find(|p| p.name == name).cloned();
                let artwork = self.artwork.clone();
                tasks.spawn(async move { inspect(&bus, name, old, artwork).await });
            }
            let mut players = Vec::new();
            while let Some(result) = tasks.join_next().await {
                if let Ok(Ok(player)) = result {
                    players.push(player);
                }
            }
            players.sort_by(|a, b| {
                b.value["playing"]
                    .as_bool()
                    .cmp(&a.value["playing"].as_bool())
                    .then(a.name.cmp(&b.name))
            });
            let snapshot = json!({"origin":"desktop","players":players.iter().map(|p| &p.value).collect::<Vec<_>>()});
            // Timeline frames stay small; negotiated peers receive full covers once
            // per content/track on the bulk link. Older companions keep previews.
            let mut wire_players: Vec<Value> = players
                .iter()
                .map(|p| {
                    let mut value = p.value.clone();
                    value["artwork"] = p.value["artwork"]["preview"].clone();
                    if value["artwork_id"].is_null() {
                        value.as_object_mut().unwrap().remove("artwork_id");
                    }
                    value
                })
                .collect();
            let wire_snapshot = loop {
                let value = json!({"origin":"desktop","players":wire_players});
                if serde_json::to_vec(&value)?.len() <= 60_000 {
                    break value;
                }
                if let Some(p) = wire_players
                    .iter_mut()
                    .rev()
                    .find(|p| !p["artwork"].is_null())
                {
                    p["artwork"] = Value::Null;
                } else if wire_players.pop().is_none() {
                    break value;
                }
            };
            *self.players.lock().await = players.clone();
            let mut state = engine.state.lock().await;
            let devices: Vec<_> = state
                .devices
                .iter()
                .filter(|(_, d)| allowed(d))
                .map(|(id, _)| id.clone())
                .collect();
            let mut live = Vec::new();
            let mut live_art = Vec::new();
            for id in devices {
                if let Some(link) = state
                    .sessions
                    .get(&id)
                    .and_then(|s| s.select(Workload::Control))
                {
                    let key = (id.clone(), link.generation.clone());
                    live.push(key.clone());
                    if sent.get(&key) != Some(&wire_snapshot)
                        && link
                            .tx
                            .try_send(Message::Event {
                                feature: "desktop_media".into(),
                                data: wire_snapshot.clone(),
                            })
                            .is_ok()
                    {
                        sent.insert(key, wire_snapshot.clone());
                    }
                }
                if !state.devices[&id]
                    .disabled
                    .iter()
                    .any(|f| f == "media_artwork")
                    && state.devices[&id]
                        .capabilities
                        .iter()
                        .any(|c| c.id == "media_artwork" && c.status == Availability::Available)
                {
                    if let Some(link) = state
                        .sessions
                        .get(&id)
                        .and_then(|s| s.select(Workload::Bulk))
                    {
                        for player in &players {
                            let Some(art_id) = player.value["artwork_id"].as_str() else {
                                continue;
                            };
                            let key = (
                                id.clone(),
                                link.generation.clone(),
                                player.value["session"].as_str().unwrap().to_owned(),
                            );
                            live_art.push(key.clone());
                            let version = format!("{}:{art_id}", player.value["track_id"]);
                            if art_sent.get(&key)!=Some(&version) && link.tx.try_send(Message::Event {feature:"desktop_media".into(),data:json!({"kind":"artwork","origin":"desktop","session":player.value["session"],"track_id":player.value["track_id"],"artwork_id":art_id,"artwork":player.value["artwork"]})}).is_ok() {
                                art_sent.insert(key,version);
                            }
                        }
                    }
                }
                let d = state.devices.get_mut(&id).unwrap();
                if d.state.get("desktop_media") != Some(&snapshot) {
                    d.state.insert("desktop_media".into(), snapshot.clone());
                    engine.changed.send(id).ok();
                }
            }
            sent.retain(|key, _| live.contains(key));
            art_sent.retain(|key, _| live_art.contains(key));
        }
        Ok(())
    }
}
