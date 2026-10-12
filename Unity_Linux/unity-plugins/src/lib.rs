//! Independent feature modules. Capability negotiation never invents remote support.
use anyhow::{bail, Result};
use serde_json::Value;
use unity_protocol::{Availability, Capability, MAX_TEXT};
pub mod battery;
pub mod browsing;
pub mod call_control;
pub mod call_notifications;
pub mod clipboard;
pub mod connectivity;
pub mod files;
pub mod find;
pub mod hotspot;
pub mod media;
pub mod network;
pub mod notification_actions;
pub mod notifications;
pub mod pairing;
pub mod photos;
pub mod previews;
pub mod reconnect;
pub mod remote_input;
pub mod ringer;
pub mod screen;
pub mod sms;
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
        Box::new(ringer::Feature),
        Box::new(find::Feature),
        Box::new(remote_input::Feature),
        Box::new(screen::Feature),
        Box::new(hotspot::Feature),
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
            if address.is_empty()
                || !address
                    .chars()
                    .all(|c| c.is_ascii_digit() || "+ -()".contains(c))
            {
                bail!("invalid phone number")
            };
            if text(v, "body", 4096)?.is_empty() {
                bail!("empty message")
            }
        }
        ("sms", "list") => {
            if v.get("address").is_some() {
                text(v, "address", 256)?;
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
        ("media", "seek") => bounded(v, "position", 0.0, 86400000.0)?,
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
        ("photos", "get") | ("browsing", "get") => {
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
        }
        "network" => {
            text(v, "kind", 32)?;
            if !v["signal"].is_null() {
                bounded(v, "signal", 0.0, 4.0)?
            }
        }
        "notifications" => {
            text(v, "id", 512)?;
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
        "media" => {
            text(v, "title", 512)?;
            text(v, "artist", 512)?;
            if !v["playing"].is_boolean() {
                bail!("missing playback state")
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
                previews::validate(image)?;
            }
        }
        "call_notifications" => {
            text(v, "state", 32)?;
            text(v, "number", 64)?;
        }
        "ringer" => {
            text(v, "mode", 32)?;
        }
        "files" => {
            text(v, "phase", 16)?;
            text(v, "id", 32)?;
        }
        "sms" | "photos" | "browsing" => {}
        _ => {
            bail!("feature does not accept events")
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn all_requirements_have_modules() {
        assert_eq!(registry().len(), 21);
        let mut ids = std::collections::HashSet::new();
        for p in registry() {
            assert!(ids.insert(p.spec().id));
        }
    }
    #[test]
    fn unavailable_stays_unavailable() {
        let c = capabilities(&[Capability::available("screen")]);
        assert_eq!(
            c.iter().find(|c| c.id == "screen").unwrap().status,
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
}
