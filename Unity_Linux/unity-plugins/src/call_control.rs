use crate::{text, validate_command, Plugin, Spec};
use anyhow::{bail, Result};
use serde_json::Value;
pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "call_control",
            title: "Call management",
            group: "telephony",
            sensitive: true,
            blocked: "",
            actions: &[
                "refresh",
                "dial",
                "answer",
                "reject",
                "hangup",
                "hold",
                "unhold",
                "mute",
                "dtmf",
                "route",
                "contacts",
                "history",
                "audio_devices",
                "audio_start",
                "audio_stop",
                "audio_mute",
                "audio_select",
                "hfp_devices",
                "hfp_select",
            ],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        let allowed: &[&str] = match action {
            "dial" => {
                number(text(data, "number", 64)?)?;
                &["number"]
            }
            "answer" | "reject" | "hangup" | "hold" | "unhold" => &["call_id"],
            "dtmf" => {
                let tone = text(data, "tone", 1)?;
                if tone.len() != 1 || !"0123456789*#ABCD".contains(tone) {
                    bail!("invalid keypad tone")
                }
                &["call_id", "tone"]
            }
            "mute" | "audio_mute" => {
                if !data["muted"].is_boolean() {
                    bail!("missing mute state")
                }
                &["muted"]
            }
            "route" => {
                if !["phone", "speaker", "wired", "bluetooth", "desktop"]
                    .contains(&text(data, "route", 16)?)
                {
                    bail!("invalid audio route")
                }
                &["route"]
            }
            "contacts" | "history" => {
                for (field, min, max) in [("offset", 0, 100_000), ("limit", 1, 100)] {
                    if let Some(v) = data.get(field) {
                        if !v.as_u64().is_some_and(|n| n >= min && n <= max) {
                            bail!("invalid page")
                        }
                    }
                }
                if data.get("query").is_some() {
                    text(data, "query", 256)?;
                }
                if action == "contacts" {
                    &["query", "offset", "limit"]
                } else {
                    &["offset", "limit"]
                }
            }
            "audio_start" => {
                address(text(data, "address", 17)?)?;
                for key in ["output", "microphone"] {
                    if let Some(value) = data.get(key) {
                        audio_device(value)?;
                    }
                }
                &["address", "output", "microphone"]
            }
            "hfp_select" => {
                address(text(data, "address", 17)?)?;
                &["address"]
            }
            "audio_select" => {
                for key in ["output", "microphone"] {
                    audio_device(&data[key])?;
                }
                &["output", "microphone"]
            }
            _ => &[],
        };
        if let Some(id) = data.get("call_id") {
            if !id.as_str().is_some_and(unity_protocol::valid_id) {
                bail!("invalid call ID")
            }
        }
        if data
            .as_object()
            .unwrap()
            .keys()
            .any(|k| !allowed.contains(&k.as_str()))
        {
            bail!("invalid call parameter")
        }
        Ok(())
    }
    fn event(&self, data: &Value) -> Result<()> {
        validate_snapshot(data)
    }
}
fn audio_device(value: &Value) -> Result<()> {
    let name = value
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("invalid call audio device"))?;
    if name.len() > 256
        || !name
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"._:-".contains(&c))
    {
        bail!("invalid call audio device");
    }
    if let Some(mac) = name.strip_prefix("bluetooth:") {
        address(mac)?;
    }
    Ok(())
}
pub fn number(s: &str) -> Result<()> {
    if s.is_empty() || s.len() > 64 || !s.chars().all(|c| c.is_ascii_digit() || "+ -()".contains(c))
    {
        bail!("invalid phone number")
    }
    let normalized: String = s
        .chars()
        .filter(|c| c.is_ascii_digit() || *c == '+')
        .collect();
    if !normalized.chars().any(|c| c.is_ascii_digit())
        || normalized.matches('+').count() > 1
        || (normalized.contains('+') && !normalized.starts_with('+'))
    {
        bail!("invalid phone number")
    }
    Ok(())
}
pub fn address(s: &str) -> Result<()> {
    if s.len() != 17
        || !s.bytes().enumerate().all(|(i, c)| {
            if i % 3 == 2 {
                c == b':'
            } else {
                c.is_ascii_hexdigit()
            }
        })
    {
        bail!("invalid Bluetooth address")
    }
    Ok(())
}
pub fn validate_snapshot(v: &Value) -> Result<()> {
    let calls = v["calls"]
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("missing calls"))?;
    if calls.len() > 16 || !v["muted"].is_boolean() {
        bail!("invalid call state")
    }
    let mut ids = std::collections::HashSet::new();
    for call in calls {
        let id = text(call, "id", 32)?;
        if !unity_protocol::valid_id(id) || !ids.insert(id) {
            bail!("invalid call ID")
        }
        if ![
            "ringing",
            "dialing",
            "connecting",
            "select_account",
            "active",
            "held",
            "disconnecting",
            "new",
        ]
        .contains(&text(call, "state", 32)?)
        {
            bail!("invalid call state")
        }
        text(call, "number", 64)?;
        text(call, "name", 256)?;
        if !call["connect_time"]
            .as_u64()
            .is_some_and(|t| t < 32_503_680_000_000)
            || !call["can_hold"].is_boolean()
            || !call["conference"].is_boolean()
        {
            bail!("invalid call details")
        }
    }
    let routes = v["routes"]
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("missing audio routes"))?;
    if routes.len() > 4
        || !["phone", "speaker", "wired", "bluetooth"].contains(&text(v, "route", 16)?)
        || routes.iter().any(|r| {
            !r.as_str()
                .is_some_and(|r| ["phone", "speaker", "wired", "bluetooth"].contains(&r))
        })
    {
        bail!("invalid audio route")
    }
    Ok(())
}
pub fn validate_result(action: &str, v: &Value) -> Result<()> {
    let key = match action {
        "contacts" => "contacts",
        "history" => "calls",
        _ => return Ok(()),
    };
    let items = v[key]
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("missing telephony list"))?;
    if items.len() > 100 {
        bail!("too many telephony entries")
    }
    if !v["next_offset"].is_null() && !v["next_offset"].as_u64().is_some_and(|n| n <= 100_100) {
        bail!("invalid page cursor")
    }
    for item in items {
        text(item, "id", 32)?;
        text(item, "number", 64)?;
        text(item, "name", 256)?;
        if action == "history"
            && (!item["timestamp"].is_u64()
                || !item["duration"].is_u64()
                || !item["type"].as_u64().is_some_and(|t| (1..=7).contains(&t)))
        {
            bail!("invalid call history")
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn validates_specific_calls_and_rejects_injected_parameters() {
        let f = Feature;
        assert!(f
            .command("dial", &json!({"number":"+1 (555) 123-4567"}))
            .is_ok());
        for n in ["+", "++12", "12+3", "*123#", "tel:123", "$(id)", "１２３"] {
            assert!(f.command("dial", &json!({"number":n})).is_err());
        }
        assert!(f
            .command("answer", &json!({"call_id":"ab".repeat(16)}))
            .is_ok());
        assert!(f.command("answer", &json!({"call_id":"old"})).is_err());
        assert!(f
            .command("route", &json!({"route":"desktop", "command":"sh"}))
            .is_err());
        assert!(f
            .command("audio_start", &json!({"address":"AA:BB:CC:DD:EE:FF"}))
            .is_ok());
        assert!(f
            .command(
                "audio_select",
                &json!({"output":"bluetooth:11:22:33:44:55:66", "microphone":""})
            )
            .is_ok());
        for selection in [
            json!({"output":"speaker sink=phone", "microphone":"mic"}),
            json!({"output":"speaker", "microphone":null}),
            json!({"output":"bluetooth:invalid", "microphone":"mic"}),
            json!({"output":"speaker"}),
        ] {
            assert!(f.command("audio_select", &selection).is_err());
        }
        assert!(f.command("history", &json!({"offset":-1})).is_err());
        assert!(f.command("contacts", &json!({"limit":101})).is_err());
    }
    #[test]
    fn rejects_untrusted_call_states() {
        let mut state = json!({"calls":[],"muted":false,"route":"phone","routes":[]});
        assert!(validate_snapshot(&state).is_ok());
        state["route"] = json!("shell");
        assert!(validate_snapshot(&state).is_err());
    }
}
