//! Independent feature modules. Capability negotiation never invents remote support.
use anyhow::{bail, Result};
use serde_json::Value;
use unity_protocol::{Availability, Capability, MAX_TEXT};
pub mod audio;
pub mod battery;
pub mod browsing;
pub mod call_control;
pub mod call_notifications;
pub mod clipboard;
pub mod connectivity;
pub mod desktop_media;
pub mod files;
pub mod find;
pub mod hotspot;
pub mod lock;
pub mod media;
pub mod network;
pub mod notification_actions;
pub mod notifications;
pub mod pairing;
pub mod phone_connectivity;
pub mod photos;
pub mod previews;
pub mod reconnect;
pub mod remote_input;
pub mod ringer;
pub mod samsung_routines;
pub mod screen;
pub mod sms;
pub mod sound;
pub mod urls;

pub mod transfer;
#[derive(Clone, Copy)]
pub struct Spec {
    pub id: &'static str,
    pub title: &'static str,
    pub group: &'static str,
    pub sensitive: bool,
    pub blocked: &'static str,
    pub actions: &'static [&'static str],
}
pub trait Plugin: Send + Sync {
    fn spec(&self) -> Spec;
    fn command(&self, action: &str, data: &Value) -> Result<()>;
    fn event(&self, data: &Value) -> Result<()>;
}
pub fn registry() -> Vec<Box<dyn Plugin>> {
    vec![
        Box::new(pairing::Feature),
        Box::new(reconnect::Feature),
        Box::new(battery::Feature),
        Box::new(network::Feature),
        Box::new(connectivity::Feature),
        Box::new(notifications::Feature),
        Box::new(notification_actions::Feature),
        Box::new(sms::Feature),
        Box::new(call_notifications::Feature),
        Box::new(call_control::Feature),
        Box::new(clipboard::Feature),
        Box::new(files::Feature),
        Box::new(photos::Feature),
        Box::new(urls::Feature),
        Box::new(browsing::Feature),
        Box::new(media::Feature),
        Box::new(sound::Feature),
        Box::new(desktop_media::Feature),
        Box::new(previews::MediaArtworkFeature),
        Box::new(audio::Feature),
        Box::new(lock::Feature),
        Box::new(ringer::Feature),
        Box::new(find::Feature),
        Box::new(remote_input::Feature),
        Box::new(screen::Feature),
        Box::new(hotspot::Feature),
        Box::new(samsung_routines::Feature),
        Box::new(phone_connectivity::Feature),
    ]
}
pub fn plugin(id: &str) -> Option<Box<dyn Plugin>> {
    registry().into_iter().find(|p| p.spec().id == id)
}
pub fn capabilities(remote: &[Capability]) -> Vec<Capability> {
    registry()
        .into_iter()
        .map(|p| {
            let s = p.spec();
            if !s.blocked.is_empty() {
                return Capability::unsupported(s.id, s.blocked);
            }
            remote
                .iter()
                .find(|c| c.id == s.id)
                .cloned()
                .unwrap_or_else(|| Capability::unsupported(s.id, "Not advertised by this device"))
        })
        .collect()
}
pub fn command_available(c: &Capability) -> bool {
    matches!(
        c.status,
        Availability::Available | Availability::UserActionRequired
    )
}
pub fn text<'a>(v: &'a Value, k: &str, max: usize) -> Result<&'a str> {
    let s = v
        .get(k)
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow::anyhow!("missing text field: {k}"))?;
    if s.len() > max || s.contains('\0') {
        bail!("invalid text field: {k}")
    }
    Ok(s)
}
fn bounded(v: &Value, k: &str, min: f64, max: f64) -> Result<()> {
    let n = v
        .get(k)
        .and_then(Value::as_f64)
        .ok_or_else(|| anyhow::anyhow!("missing numeric field: {k}"))?;
    if !n.is_finite() || n < min || n > max {
        bail!("numeric field outside allowed range: {k}")
    }
    Ok(())
}
pub fn validate_command(s: Spec, a: &str, v: &Value) -> Result<()> {
    if !s.blocked.is_empty() {
        bail!("unsupported: {}", s.blocked)
    }
    if !s.actions.contains(&a) {
        bail!("unsupported action")
    }
    if !v.is_object() || serde_json::to_vec(v)?.len() > MAX_TEXT {
        bail!("invalid command payload")
    }
    match (s.id, a) {
        ("urls", "open") => {
            let url = text(v, "url", 8192)?;
            if !(url.starts_with("https://") || url.starts_with("http://"))
                || url.chars().any(char::is_control)
            {
                bail!("only http(s) URLs are supported")
            }
        }
        ("clipboard", "set") => {
            text(v, "text", MAX_TEXT - 256)?;
        }
        ("sms", "send") => {
            let address = text(v, "address", 64)?;
            call_control::number(address)?;
            if text(v, "body", 4096)?.is_empty() {
                bail!("empty message")
            }
        }
        ("sms", "list") => {
            if v.get("address").is_some() {
                text(v, "address", 256)?;
            }
            for (key, min, max) in [("offset", 0, 100_000), ("limit", 1, 100)] {
                if let Some(n) = v.get(key) {
                    if !n.as_u64().is_some_and(|n| n >= min && n <= max) {
                        bail!("invalid message page")
                    }
                }
            }
        }
        ("notification_actions", "reply") => {
            text(v, "id", 512)?;
            text(v, "action", 64)?;
            text(v, "text", 4096)?;
        }
        ("notification_actions", "invoke") => {
            text(v, "id", 512)?;
            text(v, "action", 64)?;
        }
        ("notifications", "dismiss") => {
            text(v, "id", 512)?;
        }
        ("sound", _) => {
            let source = text(v, "source", 16)?;
            if !matches!(source, "phone" | "desktop") {
                bail!("invalid sound target")
            }
            if a != "refresh"
                && source == "desktop"
                && !v["output"].as_str().is_some_and(unity_protocol::valid_id)
            {
                bail!("invalid output identity")
            }
            if a == "volume" && !v["volume"].as_u64().is_some_and(|n| n <= 100) {
                bail!("volume must be an integer from 0 to 100")
            }
            if a == "mute" && !v["muted"].is_boolean() {
                bail!("invalid mute state")
            }
        }
        ("media" | "desktop_media", _) => {
            if let Some(session) = v.get("session") {
                if !session.as_str().is_some_and(unity_protocol::valid_id) {
                    bail!("invalid media session")
                }
            }
            if s.id == "desktop_media" && a != "refresh" {
                text(v, "session", 32)?;
            }
            if a == "seek" {
                if !v["position"].as_u64().is_some_and(|p| p <= 86400000) {
                    bail!("invalid seek position")
                }
                if s.id == "desktop_media" {
                    text(v, "track_id", 1024)?;
                }
            }
        }
        ("ringer", "set") => {
            if !["normal", "vibrate", "silent", "dnd", "off"].contains(&text(v, "mode", 32)?) {
                bail!("invalid ringer mode")
            }
        }
        ("remote_input", "text") => {
            text(v, "text", 4096)?;
        }
        ("remote_input", "tap") => {
            bounded(v, "x", 0.0, 1.0)?;
            bounded(v, "y", 0.0, 1.0)?;
        }
        ("remote_input", "swipe") => {
            for k in ["x", "y", "end_x", "end_y"] {
                bounded(v, k, 0.0, 1.0)?
            }
        }
        ("files", "send") => {
            text(v, "path", 4096)?;
        }
        ("photos", "get" | "copy") | ("browsing", "get") => {
            text(v, "id", 2048)?;
        }
        ("photos", "preview") => {
            if !unity_protocol::valid_id(text(v, "id", 32)?) {
                bail!("invalid photo selection")
            }
            if v.get("edge").is_some() {
                let edge = v["edge"].as_u64().unwrap_or(0);
                if !(64..=320).contains(&edge) {
                    bail!("preview edge must be 64..320")
                }
            }
        }
        ("browsing", "list") => {
            text(v, "id", 2048)?;
        }
        _ => {}
    }
    Ok(())
}
pub fn validate_event(id: &str, v: &Value) -> Result<()> {
    if !v.is_object() || serde_json::to_vec(v)?.len() > unity_protocol::MAX_FRAME - 1024 {
        bail!("invalid event")
    }
    match id {
        "battery" => {
            bounded(v, "level", 0.0, 100.0)?;
            if !v["charging"].is_boolean() {
                bail!("missing charging state")
            }
            battery::validate_telemetry(v)?;
        }
        "network" => {
            text(v, "kind", 32)?;
            if !v["signal"].is_null() {
                bounded(v, "signal", 0.0, 4.0)?
            }
        }
        "notifications" => {
            text(v, "id", 512)?;
            if v.get("alert").is_some_and(|v| !v.is_boolean()) {
                bail!("invalid notification alert state")
            }
            if let Some(id) = v.get("sms_id") {
                sms_inbox_id(id)?;
            }
            if !v["removed"].as_bool().unwrap_or(false) {
                text(v, "app", 256)?;
                text(v, "title", 512)?;
                text(v, "text", 8192)?;
                if v["actions"].as_array().is_some_and(|a| a.len() > 16) {
                    bail!("too many notification actions")
                }
            }
        }
        "clipboard" => {
            text(v, "text", MAX_TEXT - 256)?;
        }
        "urls" => {
            let url = text(v, "url", 8192)?;
            if !(url.starts_with("https://") || url.starts_with("http://"))
                || url.chars().any(char::is_control)
            {
                bail!("only http(s) URLs are supported")
            }
        }
        "sound" => {
            if v["origin"] != "phone"
                || !v["available"].is_boolean()
                || !v["muted"].is_boolean()
                || !v["volume"].as_u64().is_some_and(|n| n <= 100)
            {
                bail!("invalid phone volume state")
            }
            text(v, "name", 512)?;
            text(v, "error", 512)?;
        }
        "media" => {
            if v["kind"] == "artwork" {
                return previews::validate_media_update(v);
            }
            if let Some(origin) = v.get("origin") {
                if origin != "phone" {
                    bail!("media is not phone-native")
                }
            }
            if let Some(players) = v.get("players") {
                let players = players
                    .as_array()
                    .filter(|p| p.len() <= 16)
                    .ok_or_else(|| anyhow::anyhow!("invalid media players"))?;
                let mut ids = std::collections::HashSet::new();
                for player in players {
                    if player["origin"] != "phone"
                        || !player["session"]
                            .as_str()
                            .is_some_and(unity_protocol::valid_id)
                        || player.get("players").is_some()
                        || !ids.insert(player["session"].as_str().unwrap())
                    {
                        bail!("invalid player origin or session")
                    }
                    validate_event("media", player)?;
                }
            }
            text(v, "title", 512)?;
            text(v, "artist", 512)?;
            if !v["playing"].is_boolean() {
                bail!("missing playback state")
            }
            if let Some(active) = v.get("active") {
                if !active.is_boolean() {
                    bail!("invalid active media flag")
                }
            }
            if let Some(actions) = v.get("actions") {
                let actions = actions
                    .as_array()
                    .filter(|a| a.len() <= 5)
                    .ok_or_else(|| anyhow::anyhow!("invalid media actions"))?;
                if actions.iter().any(|a| {
                    !a.as_str().is_some_and(|a| {
                        matches!(a, "play" | "pause" | "next" | "previous" | "seek")
                    })
                }) {
                    bail!("invalid media action")
                }
            }
            for field in ["album", "app_name", "package"] {
                if v.get(field).is_some() {
                    text(v, field, 512)?;
                }
            }
            for field in ["position", "duration"] {
                if v.get(field).is_some() {
                    bounded(v, field, 0.0, 86400000.0)?;
                }
            }
            if let Some(image) = v.get("artwork").filter(|image| !image.is_null()) {
                previews::validate_media(image)?;
            }
        }
        "call_notifications" => {
            text(v, "state", 32)?;
            text(v, "number", 64)?;
        }
        "ringer" => {
            text(v, "mode", 32)?;
        }
        "find" => {
            if !["ringing", "stopped"].contains(&text(v, "state", 32)?) {
                bail!("invalid find state")
            }
        }
        "files" => {
            text(v, "phase", 16)?;
            text(v, "id", 32)?;
        }
        "sms" => {
            if !v["revision"].is_u64() {
                bail!("missing message revision")
            }
            if v.get("message_id").is_some()
                && (!unity_protocol::valid_id(text(v, "message_id", 32)?)
                    || ![
                        "submitted_to_modem",
                        "sent",
                        "delivered",
                        "failed",
                        "delivery_failed",
                    ]
                    .contains(&text(v, "state", 32)?))
            {
                bail!("invalid message report")
            }
            if let Some(incoming) = v.get("incoming") {
                sms_inbox_id(&incoming["id"])?;
                text(incoming, "address", 256)?;
                text(incoming, "body", 4096)?;
                if !incoming["timestamp"]
                    .as_u64()
                    .is_some_and(|t| t < 32_503_680_000_000)
                {
                    bail!("invalid incoming message timestamp")
                }
            }
        }
        "photos" | "browsing" => {}
        _ => {
            bail!("feature does not accept events")
        }
    }
    Ok(())
}
fn sms_inbox_id(v: &Value) -> Result<()> {
    if !v
        .as_str()
        .is_some_and(|s| !s.is_empty() && s.len() <= 20 && s.bytes().all(|c| c.is_ascii_digit()))
    {
        bail!("invalid SMS inbox ID")
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn find_state_accepts_only_ringing_and_stopped() {
        for state in ["ringing", "stopped"] {
            validate_event("find", &json!({"state":state})).unwrap();
        }
        for data in [json!({}), json!({"state":123}), json!({"state":"playing"})] {
            assert!(validate_event("find", &data).is_err());
        }
    }
    #[test]
    fn all_requirements_have_modules() {
        assert_eq!(registry().len(), 28);
        let mut ids = std::collections::HashSet::new();
        for p in registry() {
            assert!(ids.insert(p.spec().id));
        }
    }
    #[test]
    fn media_sources_and_controls_have_bounded_native_sessions() {
        let player = json!({"origin":"phone","session":"ab".repeat(16),"title":"Track","artist":"Artist","playing":false,"active":true,"actions":["play","next"]});
        let mut snapshot = player.clone();
        snapshot["players"] = json!([player]);
        assert!(plugin("media").unwrap().event(&snapshot).is_ok());
        for malformed in [
            json!([snapshot.clone()]),
            json!([
                snapshot["players"][0].clone(),
                snapshot["players"][0].clone()
            ]),
        ] {
            let mut invalid = snapshot.clone();
            invalid["players"] = malformed;
            assert!(plugin("media").unwrap().event(&invalid).is_err());
        }
        snapshot["players"][0]["origin"] = json!("desktop");
        assert!(plugin("media").unwrap().event(&snapshot).is_err());
        let plugin = plugin("desktop_media").unwrap();
        assert!(plugin
            .command("next", &json!({"session":"ab".repeat(16)}))
            .is_ok());
        assert!(plugin.command("next", &json!({})).is_err());
        assert!(plugin
            .command(
                "seek",
                &json!({"session":"ab".repeat(16),"position":2.5,"track_id":"/track"})
            )
            .is_err());
        assert!(plugin
            .command(
                "seek",
                &json!({"session":"ab".repeat(16),"position":2000,"track_id":"/track"})
            )
            .is_ok());
    }
    #[test]
    fn unavailable_stays_unavailable() {
        let c = capabilities(&[Capability::unsupported(
            "call_control",
            "Dialer permission required",
        )]);
        assert_eq!(
            c.iter().find(|c| c.id == "call_control").unwrap().status,
            Availability::Unsupported
        );
    }
    #[test]
    fn invalid_remote_commands_are_rejected() {
        assert!(plugin("urls")
            .unwrap()
            .command("open", &json!({"url":"file:///etc/passwd"}))
            .is_err());
        assert!(plugin("remote_input")
            .unwrap()
            .command("tap", &json!({"x":9,"y":0}))
            .is_err());
        assert!(plugin("battery")
            .unwrap()
            .event(&json!({"level":101,"charging":false}))
            .is_err());
    }
    #[test]
    fn every_module_rejects_unknown_commands() {
        for p in registry() {
            assert!(p.command("invalid", &json!({})).is_err());
        }
    }
    #[test]
    fn incoming_sms_and_notification_alert_metadata_are_bounded() {
        let sms = json!({"revision":1,"incoming":{"id":"42","address":"Sender","body":"Hello","timestamp":1000}});
        assert!(validate_event("sms", &sms).is_ok());
        for value in [
            json!({"revision":1,"incoming":{"id":"../42","address":"Sender","body":"Hello","timestamp":1000}}),
            json!({"revision":1,"incoming":{"id":"42","address":"Sender","body":"Hello","timestamp":-1}}),
            json!({"revision":1,"incoming":{"id":"42","address":"Sender","body":"x".repeat(4097),"timestamp":1000}}),
        ] {
            assert!(validate_event("sms", &value).is_err());
        }
        let notification = json!({"id":"post","app":"chat","title":"Alex","text":"Hello","removed":false,"alert":false,"sms_id":"42"});
        assert!(validate_event("notifications", &notification).is_ok());
        let mut invalid = notification;
        invalid["alert"] = json!("true");
        assert!(validate_event("notifications", &invalid).is_err());
    }
}
