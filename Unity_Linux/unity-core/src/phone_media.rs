//! Phone-native sessions exported to desktop media controls, never rediscovered as laptop media.
use crate::{Device, Engine};
use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    os::unix::{ffi::OsStrExt, fs::DirBuilderExt},
    path::{Path, PathBuf},
    sync::{Arc, RwLock, Weak},
    time::{Duration, Instant},
};
use tokio::{sync::watch, time::interval};
use unity_protocol::Availability;
use zbus::{
    fdo,
    zvariant::{OwnedObjectPath, Value as Variant},
};

const PATH: &str = "/org/mpris/MediaPlayer2";
const PLAYER: &str = "org.mpris.MediaPlayer2.Player";
type Metadata = HashMap<String, Variant<'static>>;

async fn submit(
    engine: &Arc<Engine>,
    device: &str,
    feature: &str,
    action: &str,
    data: Value,
) -> fdo::Result<()> {
    let id = engine
        .execute(device, feature, action, data, "system-media")
        .await
        .map_err(|e| fdo::Error::Failed(e.to_string()))?;
    // Native media controls have no operation-history reader; retire their completed
    // requests under history pressure just like a result already read by the GTK client.
    if let Some(operation) = engine.state.lock().await.operations.get_mut(&id) {
        operation.observed = true;
    }
    Ok(())
}

fn allowed(device: &Device) -> bool {
    device.connected
        && !device.disabled.iter().any(|f| f == "media")
        && device
            .capabilities
            .iter()
            .any(|c| c.id == "media" && c.status == Availability::Available)
}
fn file_uri(path: &Path) -> String {
    let mut uri = String::from("file://");
    for &byte in path.as_os_str().as_bytes() {
        if byte.is_ascii_alphanumeric() || b"/-_.~".contains(&byte) {
            uri.push(byte as char);
        } else {
            uri.push_str(&format!("%{byte:02X}"));
        }
    }
    uri
}
#[derive(Clone)]
struct Snapshot {
    value: Value,
    sound: Value,
    label: String,
    art: String,
    sampled: Instant,
}
impl Snapshot {
    fn action(&self, action: &str) -> bool {
        self.value["actions"]
            .as_array()
            .is_some_and(|a| a.iter().any(|v| v == action))
    }
    fn rate(&self) -> f64 {
        self.value["speed"].as_f64().unwrap_or(1.0).clamp(0.0, 4.0)
    }
    fn position(&self) -> i64 {
        let extra = if self.value["playing"] == true {
            self.sampled.elapsed().as_secs_f64() * self.rate() * 1_000_000.0
        } else {
            0.0
        };
        let duration = self.value["duration"]
            .as_i64()
            .filter(|d| *d > 0)
            .unwrap_or(86_400_000);
        (self.value["position"].as_i64().unwrap_or(0) * 1000 + extra as i64)
            .clamp(0, duration * 1000)
    }
    fn track(&self) -> OwnedObjectPath {
        let hash = hex::encode(Sha256::digest(format!(
            "{}\0{}",
            self.value["session"], self.value["track_id"]
        )));
        OwnedObjectPath::try_from(format!("/org/unityconnect/tracks/t_{hash}")).unwrap()
    }
    fn metadata(&self) -> Metadata {
        let mut result = HashMap::from([
            ("mpris:trackid".into(), Variant::from(self.track())),
            (
                "mpris:length".into(),
                Variant::from(self.value["duration"].as_i64().unwrap_or(0) * 1000),
            ),
            (
                "xesam:title".into(),
                Variant::from(self.value["title"].as_str().unwrap_or("").to_owned()),
            ),
            (
                "xesam:artist".into(),
                Variant::from(vec![self.value["artist"].as_str().unwrap_or("").to_owned()]),
            ),
            (
                "xesam:album".into(),
                Variant::from(self.value["album"].as_str().unwrap_or("").to_owned()),
            ),
        ]);
        if !self.art.is_empty() {
            result.insert("mpris:artUrl".into(), Variant::from(self.art.clone()));
        }
        result
    }
    fn volume(&self) -> f64 {
        if self.sound["muted"] == true {
            0.0
        } else {
            self.sound["volume"].as_f64().unwrap_or(100.0) / 100.0
        }
    }
    fn properties(&self) -> Metadata {
        let mut result = HashMap::from([
            ("Metadata".into(), Variant::from(self.metadata())),
            (
                "PlaybackStatus".into(),
                Variant::from(if self.value["playing"] == true {
                    "Playing"
                } else {
                    "Paused"
                }),
            ),
            ("Rate".into(), Variant::from(self.rate())),
            ("Volume".into(), Variant::from(self.volume())),
            ("CanControl".into(), Variant::from(true)),
        ]);
        for (property, action) in [
            ("CanPlay", "play"),
            ("CanPause", "pause"),
            ("CanGoNext", "next"),
            ("CanGoPrevious", "previous"),
            ("CanSeek", "seek"),
        ] {
            result.insert(property.into(), Variant::from(self.action(action)));
        }
        result
    }
}
#[derive(Clone)]
struct Remote {
    engine: Weak<Engine>,
    device: String,
    session: String,
    snapshot: Arc<RwLock<Snapshot>>,
}
impl Remote {
    fn current(&self) -> Snapshot {
        self.snapshot.read().unwrap().clone()
    }
    async fn command(&self, action: &str, position: Option<i64>) -> fdo::Result<()> {
        let engine = self
            .engine
            .upgrade()
            .ok_or_else(|| fdo::Error::Failed("Unity stopped".into()))?;
        let current = self.current();
        {
            let state = engine.state.lock().await;
            let device = state
                .devices
                .get(&self.device)
                .filter(|d| allowed(d))
                .ok_or_else(|| fdo::Error::Failed("Phone media is unavailable".into()))?;
            if !device
                .state
                .get("media")
                .and_then(|m| m["players"].as_array())
                .is_some_and(|players| {
                    players.iter().any(|p| {
                        p["session"] == self.session && p["track_id"] == current.value["track_id"]
                    })
                })
            {
                return Err(fdo::Error::Failed(
                    "The phone player or track changed".into(),
                ));
            }
        }
        if !current.action(action) {
            return Ok(());
        }
        let mut data = json!({"session":self.session});
        if let Some(position) = position {
            data["position"] = json!(position / 1000);
            data["track_id"] = current.value["track_id"].clone();
        }
        submit(&engine, &self.device, "media", action, data).await
    }
}
struct Root(Remote);
#[zbus::interface(name = "org.mpris.MediaPlayer2")]
impl Root {
    fn raise(&self) {}
    fn quit(&self) {}
    #[zbus(property)]
    fn can_quit(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn can_raise(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn has_track_list(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn identity(&self) -> String {
        self.0.current().label
    }
    #[zbus(property)]
    fn desktop_entry(&self) -> &str {
        "org.unityconnect.Unity"
    }
    #[zbus(property)]
    fn supported_uri_schemes(&self) -> Vec<String> {
        vec![]
    }
    #[zbus(property)]
    fn supported_mime_types(&self) -> Vec<String> {
        vec![]
    }
}
struct Player(Remote);
#[zbus::interface(name = "org.mpris.MediaPlayer2.Player")]
impl Player {
    async fn play(&self) -> fdo::Result<()> {
        self.0.command("play", None).await
    }
    async fn pause(&self) -> fdo::Result<()> {
        self.0.command("pause", None).await
    }
    async fn play_pause(&self) -> fdo::Result<()> {
        self.0
            .command(
                if self.0.current().value["playing"] == true {
                    "pause"
                } else {
                    "play"
                },
                None,
            )
            .await
    }
    async fn stop(&self) -> fdo::Result<()> {
        self.pause().await
    }
    async fn next(&self) -> fdo::Result<()> {
        self.0.command("next", None).await
    }
    async fn previous(&self) -> fdo::Result<()> {
        self.0.command("previous", None).await
    }
    async fn seek(&self, offset: i64) -> fdo::Result<()> {
        let current = self.0.current();
        let end = current.value["duration"]
            .as_i64()
            .filter(|d| *d > 0)
            .unwrap_or(86_400_000)
            * 1000;
        self.0
            .command(
                "seek",
                Some(current.position().saturating_add(offset).clamp(0, end)),
            )
            .await
    }
    async fn set_position(&self, track_id: OwnedObjectPath, position: i64) -> fdo::Result<()> {
        let current = self.0.current();
        let end = current.value["duration"]
            .as_i64()
            .filter(|d| *d > 0)
            .unwrap_or(86_400_000)
            * 1000;
        if track_id != current.track() || !(0..=end).contains(&position) {
            return Ok(());
        }
        self.0.command("seek", Some(position)).await
    }
    fn open_uri(&self, _uri: &str) -> fdo::Result<()> {
        Err(fdo::Error::NotSupported("Open media on your phone".into()))
    }
    #[zbus(signal)]
    async fn seeked(
        emitter: &zbus::object_server::SignalEmitter<'_>,
        position: i64,
    ) -> zbus::Result<()>;
    #[zbus(property)]
    fn playback_status(&self) -> &str {
        if self.0.current().value["playing"] == true {
            "Playing"
        } else {
            "Paused"
        }
    }
    #[zbus(property)]
    fn metadata(&self) -> Metadata {
        self.0.current().metadata()
    }
    #[zbus(property(emits_changed_signal = "false"))]
    fn position(&self) -> i64 {
        self.0.current().position()
    }
    #[zbus(property)]
    fn rate(&self) -> f64 {
        self.0.current().rate()
    }
    #[zbus(property)]
    fn set_rate(&self, rate: f64) -> fdo::Result<()> {
        if rate == self.rate() {
            Ok(())
        } else {
            Err(fdo::Error::NotSupported(
                "Phone playback speed is controlled by its app".into(),
            ))
        }
    }
    #[zbus(property)]
    fn minimum_rate(&self) -> f64 {
        self.rate()
    }
    #[zbus(property)]
    fn maximum_rate(&self) -> f64 {
        self.rate()
    }
    #[zbus(property)]
    fn volume(&self) -> f64 {
        self.0.current().volume()
    }
    #[zbus(property)]
    async fn set_volume(&self, volume: f64) -> fdo::Result<()> {
        if !volume.is_finite() {
            return Err(fdo::Error::InvalidArgs("Invalid volume".into()));
        }
        let engine = self
            .0
            .engine
            .upgrade()
            .ok_or_else(|| fdo::Error::Failed("Unity stopped".into()))?;
        let device = engine
            .device(&self.0.device)
            .await
            .map_err(|e| fdo::Error::Failed(e.to_string()))?;
        if !allowed(&device)
            || device
                .state
                .get("sound")
                .is_none_or(|s| s["available"] != true || s["fixed"] == true)
        {
            return Err(fdo::Error::NotSupported(
                "Phone volume control is unavailable".into(),
            ));
        }
        submit(
            &engine,
            &self.0.device,
            "sound",
            "volume",
            json!({"source":"phone","volume":(volume.clamp(0.0,1.0)*100.0).round() as u64}),
        )
        .await
    }
    #[zbus(property)]
    fn can_go_next(&self) -> bool {
        self.0.current().action("next")
    }
    #[zbus(property)]
    fn can_go_previous(&self) -> bool {
        self.0.current().action("previous")
    }
    #[zbus(property)]
    fn can_play(&self) -> bool {
        self.0.current().action("play")
    }
    #[zbus(property)]
    fn can_pause(&self) -> bool {
        self.0.current().action("pause")
    }
    #[zbus(property)]
    fn can_seek(&self) -> bool {
        self.0.current().action("seek")
    }
    #[zbus(property)]
    fn can_control(&self) -> bool {
        true
    }
}
struct Export {
    bus: zbus::Connection,
    remote: Remote,
}
pub(super) async fn run(engine: Arc<Engine>, mut stop: watch::Receiver<bool>) -> Result<()> {
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(engine.dir.join("media-artwork"))?;
    let mut changes = engine.changed.subscribe();
    let mut ticks = interval(Duration::from_secs(1));
    let mut exports = HashMap::<(String, String), Export>::new();
    let mut artwork = HashMap::<String, PathBuf>::new();
    loop {
        tokio::select! { _ = stop.changed() => break, _ = changes.recv() => {}, _ = ticks.tick() => {} }
        if *stop.borrow() {
            break;
        }
        let devices = engine.devices().await;
        let mut live = HashMap::new();
        let mut live_art = std::collections::HashSet::new();
        for device in devices.iter().filter(|d| allowed(d)) {
            let Some(players) = device
                .state
                .get("media")
                .and_then(|m| m["players"].as_array())
            else {
                continue;
            };
            for value in players
                .iter()
                .filter(|p| p["origin"] == "phone" && p["active"] != false)
                .take(16)
            {
                let Some(session) = value["session"]
                    .as_str()
                    .filter(|id| unity_protocol::valid_id(id))
                else {
                    continue;
                };
                if live.len() >= 32 {
                    break;
                }
                let mut art = String::new();
                if let Some(image) = value.get("artwork").filter(|image| image.is_object()) {
                    if unity_plugins::previews::validate_media(image).is_ok() {
                        let data = image["data"].as_str().context("Missing cover data")?;
                        let hash = hex::encode(Sha256::digest(data));
                        let path = engine.dir.join("media-artwork").join(format!("{hash}.jpg"));
                        if artwork.contains_key(&hash)
                            || unity_security::write_private(&path, &STANDARD.decode(data)?).is_ok()
                        {
                            art = file_uri(&path);
                            live_art.insert(hash.clone());
                            artwork.insert(hash, path);
                        }
                    }
                }
                let key = (device.id.clone(), session.to_owned());
                let mut next = Snapshot {
                    value: value.clone(),
                    sound: device.state.get("sound").cloned().unwrap_or(Value::Null),
                    label: format!(
                        "{} on {}",
                        value["app_name"]
                            .as_str()
                            .filter(|s| !s.is_empty())
                            .unwrap_or("Phone media"),
                        device.name
                    ),
                    art,
                    sampled: Instant::now(),
                };
                if let Some(export) = exports.get(&key) {
                    let old = export.remote.current();
                    let stable_clock = ["track_id", "position", "playing", "speed"]
                        .iter()
                        .all(|k| old.value[k] == next.value[k]);
                    if stable_clock {
                        next.sampled = old.sampled;
                    }
                    let discontinuity = old.track() == next.track()
                        && (next.position() - old.position()).abs() > 2_000_000;
                    let changed = old.properties() != next.properties();
                    *export.remote.snapshot.write().unwrap() = next.clone();
                    if changed {
                        export
                            .bus
                            .emit_signal(
                                None::<&str>,
                                PATH,
                                "org.freedesktop.DBus.Properties",
                                "PropertiesChanged",
                                &(PLAYER, next.properties(), Vec::<&str>::new()),
                            )
                            .await
                            .ok();
                    }
                    if old.label != next.label {
                        export
                            .bus
                            .emit_signal(
                                None::<&str>,
                                PATH,
                                "org.freedesktop.DBus.Properties",
                                "PropertiesChanged",
                                &(
                                    "org.mpris.MediaPlayer2",
                                    HashMap::from([(
                                        "Identity",
                                        Variant::from(next.label.clone()),
                                    )]),
                                    Vec::<&str>::new(),
                                ),
                            )
                            .await
                            .ok();
                    }
                    if discontinuity {
                        export
                            .bus
                            .emit_signal(None::<&str>, PATH, PLAYER, "Seeked", &(next.position(),))
                            .await
                            .ok();
                    }
                } else {
                    let remote = Remote {
                        engine: Arc::downgrade(&engine),
                        device: device.id.clone(),
                        session: session.to_owned(),
                        snapshot: Arc::new(RwLock::new(next)),
                    };
                    let name = format!(
                        "org.mpris.MediaPlayer2.unityconnect.d_{}.s_{session}",
                        device.id
                    );
                    let bus = zbus::connection::Builder::session()?
                        .name(name)?
                        .serve_at(PATH, Root(remote.clone()))?
                        .serve_at(PATH, Player(remote.clone()))?
                        .build()
                        .await;
                    match bus {
                        Ok(bus) => {
                            exports.insert(key.clone(), Export { bus, remote });
                        }
                        Err(error) => {
                            tracing::debug!(%error, "Could not export phone player");
                        }
                    }
                }
                live.insert(key, ());
            }
        }
        exports.retain(|key, _| live.contains_key(key));
        artwork.retain(|hash, path| {
            if live_art.contains(hash) {
                true
            } else {
                std::fs::remove_file(path).ok();
                false
            }
        });
    }
    exports.clear();
    for path in artwork.values() {
        std::fs::remove_file(path).ok();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn file_uris_preserve_unicode_and_reserved_filenames() {
        assert_eq!(
            file_uri(Path::new("/tmp/a #?.jpg")),
            "file:///tmp/a%20%23%3F.jpg"
        );
        assert_eq!(file_uri(Path::new("/tmp/é.jpg")), "file:///tmp/%C3%A9.jpg");
    }
}
