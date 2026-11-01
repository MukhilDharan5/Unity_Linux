//! Artwork work never holds up metadata or the playback clock.
use serde_json::Value;
use std::{
    collections::HashMap,
    path::PathBuf,
    process::Stdio,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{process::Command, sync::Mutex, time::timeout};
struct Entry {
    image: Option<Value>,
    retry: Instant,
    loading: bool,
}
#[derive(Default)]
pub(super) struct Cache {
    entries: Mutex<HashMap<String, Entry>>,
}
impl Cache {
    pub async fn image(self: &Arc<Self>, uri: &str, track: &str) -> Option<Value> {
        if uri.len() > 8192
            || !["file://", "https://", "http://"]
                .iter()
                .any(|prefix| uri.starts_with(prefix))
        {
            return None;
        }
        let key = format!("{track}\0{uri}");
        let mut entries = self.entries.lock().await;
        if entries.len() >= 64 && !entries.contains_key(&key) {
            entries.retain(|_, entry| entry.loading || entry.retry > Instant::now());
        }
        if entries.len() >= 64 && !entries.contains_key(&key) {
            return None;
        }
        let entry = entries.entry(key.clone()).or_insert(Entry {
            image: None,
            retry: Instant::now(),
            loading: false,
        });
        let image = entry.image.clone();
        if !entry.loading && entry.retry <= Instant::now() {
            entry.loading = true;
            let cache = self.clone();
            let uri = uri.to_owned();
            tokio::spawn(async move {
                let image = load(&uri).await;
                let mut entries = cache.entries.lock().await;
                if let Some(entry) = entries.get_mut(&key) {
                    entry.loading = false;
                    entry.retry =
                        Instant::now() + Duration::from_secs(if image.is_some() { 60 } else { 3 });
                    if image.is_some() {
                        entry.image = image;
                    }
                }
            });
        }
        image
    }
}
async fn load(uri: &str) -> Option<Value> {
    let installed = std::env::current_exe()
        .ok()?
        .with_file_name("media-thumbnail.py");
    let helper = if installed.is_file() {
        installed
    } else if cfg!(debug_assertions) {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unity-media/thumbnail.py")
    } else {
        return None;
    };
    let output = timeout(
        Duration::from_secs(3),
        Command::new("/usr/bin/python3")
            .arg(helper)
            .arg(uri)
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() || output.stdout.len() > 280 * 1024 {
        return None;
    }
    let image: Value = serde_json::from_slice(&output.stdout).ok()?;
    unity_plugins::previews::validate_media(&image).ok()?;
    Some(image)
}

/// Merge independently delivered covers, including a cover arriving before its
/// small metadata frame on the other transport. Track/hash checks prevent reuse.
pub(super) fn phone_event(device: &mut super::Device, mut value: Value) {
    if value["kind"] == "artwork" {
        let key = value["artwork_id"].as_str().unwrap().to_owned();
        device.phone_artwork.insert(key, value["artwork"].clone());
        while device.phone_artwork.len() > 32 {
            device.phone_artwork.pop_first();
        }
        if let Some(media) = device.state.get_mut("media") {
            apply(media, &device.phone_artwork);
        }
    } else {
        apply(&mut value, &device.phone_artwork);
        if value["players"].as_array().is_some_and(|p| p.is_empty()) {
            device.phone_artwork.clear();
        }
        device.state.insert("media".into(), value);
    }
}
fn apply(player: &mut Value, images: &std::collections::BTreeMap<String, Value>) {
    if let Some(image) = player["artwork_id"].as_str().and_then(|id| images.get(id)) {
        player["artwork"] = image.clone();
    }
    if let Some(players) = player["players"].as_array_mut() {
        for p in players {
            apply(p, images);
        }
    }
}
