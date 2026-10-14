use crate::{validate_command, validate_event, Plugin, Spec};
use anyhow::{bail, Result};
use serde_json::Value;
pub struct Feature;
pub const MAX_REMAINING_SECONDS: u64 = 30 * 24 * 60 * 60;

/// Optional telemetry extends the v1 event without breaking level-only companions.
pub fn validate_telemetry(data: &Value) -> Result<()> {
    for (field, min, max) in [
        ("voltage_v", 1.0, 10.0),
        ("current_ma", -20_000.0, 20_000.0),
        ("power_w", -200.0, 200.0),
    ] {
        if data.get(field).is_some_and(|v| !v.is_null()) {
            crate::bounded(data, field, min, max)?;
        }
    }
    if data.get("plugged").is_some_and(|v| !v.is_boolean()) {
        bail!("invalid battery plugged state")
    }
    for (field, allowed) in [
        (
            "status",
            &["charging", "discharging", "full", "not_charging", "unknown"][..],
        ),
        ("estimate_kind", &["empty", "full"][..]),
        (
            "estimate_source",
            &["none", "android", "charge_counter", "battery_trend"][..],
        ),
        (
            "estimate_state",
            &["available", "collecting", "unavailable"][..],
        ),
    ] {
        if data.get(field).is_some() && !allowed.contains(&crate::text(data, field, 32)?) {
            bail!("invalid battery {field}")
        }
    }
    if let Some(value) = data.get("remaining_seconds").filter(|v| !v.is_null()) {
        if value
            .as_u64()
            .is_none_or(|seconds| seconds > MAX_REMAINING_SECONDS)
        {
            bail!("invalid battery remaining_seconds")
        }
        if data["estimate_state"] != "available"
            || !matches!(data["estimate_kind"].as_str(), Some("empty" | "full"))
            || data["estimate_source"].as_str().is_none_or(|s| s == "none")
        {
            bail!("battery estimate lacks its kind, source or available state")
        }
    }
    Ok(())
}

impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "battery",
            title: "Battery",
            group: "connectivity",
            sensitive: false,
            blocked: "",
            actions: &["refresh"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)
    }
    fn event(&self, data: &Value) -> Result<()> {
        validate_event(self.spec().id, data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn accepts_old_companions_and_bounded_telemetry() {
        assert!(Feature.event(&json!({"level":73,"charging":false})).is_ok());
        let event = json!({"level":73,"charging":false,"plugged":false,"status":"discharging",
            "voltage_v":3.8,"current_ma":-500.0,"power_w":1.9,"remaining_seconds":21600,
            "estimate_kind":"empty","estimate_source":"charge_counter","estimate_state":"available"});
        assert!(Feature.event(&event).is_ok());
        for (field, value) in [
            ("power_w", json!(201)),
            ("current_ma", json!(-20001)),
            ("voltage_v", json!(0)),
            ("remaining_seconds", json!(-1)),
            ("remaining_seconds", json!(1.5)),
            ("remaining_seconds", json!(MAX_REMAINING_SECONDS + 1)),
            ("estimate_kind", json!("forever")),
            ("estimate_source", json!("none")),
            ("estimate_state", json!("collecting")),
            ("plugged", json!("yes")),
        ] {
            let mut invalid = event.clone();
            invalid[field] = value;
            assert!(Feature.event(&invalid).is_err(), "{field}");
        }
        assert!(Feature
            .event(&json!({"level":73,"charging":false,"power_w":null,
            "voltage_v":null,"current_ma":null,"remaining_seconds":null,
            "estimate_kind":"empty","estimate_source":"none","estimate_state":"collecting"}))
            .is_ok());
    }
}
