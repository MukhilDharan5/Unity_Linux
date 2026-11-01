use crate::{text, validate_command, Plugin, Spec};
use anyhow::{ensure, Result};
use serde_json::Value;

pub fn address(value: &str) -> bool {
    value.len() == 17
        && value.bytes().enumerate().all(|(index, byte)| {
            if index % 3 == 2 {
                byte == b':'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
}

pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "phone_connectivity",
            title: "Phone connectivity",
            group: "connectivity",
            sensitive: true,
            blocked: "",
            actions: &["refresh"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        ensure!(
            data.as_object().is_some_and(|v| v.is_empty()),
            "Refresh requires an empty payload"
        );
        Ok(())
    }
    fn event(&self, data: &Value) -> Result<()> {
        validate(data)
    }
}

pub fn validate(data: &Value) -> Result<()> {
    ensure!(data.is_object(), "Invalid phone connectivity state");
    for field in ["hotspot_enabled", "bluetooth_enabled"] {
        ensure!(
            data.get(field)
                .is_some_and(|v| v.is_null() || v.is_boolean()),
            "Invalid radio state"
        );
    }
    let status = text(data, "bluetooth_status", 32)?;
    ensure!(
        matches!(
            status,
            "available"
                | "partial"
                | "off"
                | "permission_required"
                | "unsupported"
                | "unavailable"
                | "disabled"
        ),
        "Invalid Bluetooth status"
    );
    ensure!(
        data["complete"].is_boolean(),
        "Missing Bluetooth coverage flag"
    );
    text(data, "reason", 512)?;
    let devices = data["devices"]
        .as_array()
        .filter(|v| v.len() <= 64)
        .ok_or_else(|| anyhow::anyhow!("Invalid Bluetooth device list"))?;
    ensure!(
        devices.is_empty()
            || data["bluetooth_enabled"] == true && matches!(status, "available" | "partial"),
        "Devices require connected Bluetooth access"
    );
    let mut addresses = std::collections::HashSet::new();
    for device in devices {
        let id = text(device, "address", 17)?;
        ensure!(
            address(id) && id == id.to_ascii_uppercase() && addresses.insert(id),
            "Invalid or duplicate Bluetooth address"
        );
        text(device, "name", 256)?;
        let profiles = device["profiles"]
            .as_array()
            .filter(|v| v.len() <= 8)
            .ok_or_else(|| anyhow::anyhow!("Invalid Bluetooth profiles"))?;
        ensure!(
            profiles.iter().all(|v| v.as_str().is_some_and(|v| matches!(
                v,
                "ACL" | "Audio" | "Calls" | "Hearing aid" | "HID" | "LE Audio" | "BLE" | "Unity"
            ))),
            "Invalid Bluetooth profile"
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn radio_snapshots_require_bounded_connected_unique_devices() {
        let state = json!({"hotspot_enabled":true,"bluetooth_enabled":true,"bluetooth_status":"available","complete":false,"reason":"Android reports",
            "devices":[{"address":"AA:BB:CC:DD:EE:FF","name":"Headphones","profiles":["Audio","Calls"]}]});
        validate(&state).unwrap();
        for field in ["hotspot_enabled", "bluetooth_enabled"] {
            let mut invalid = state.clone();
            invalid[field] = json!(1);
            assert!(validate(&invalid).is_err());
        }
        for field in ["bluetooth_enabled", "complete"] {
            let mut invalid = state.clone();
            invalid[field] = Value::Null;
            assert!(validate(&invalid).is_err());
        }
        for value in ["permission_required", "off", "unsupported"] {
            let mut invalid = state.clone();
            invalid["bluetooth_status"] = json!(value);
            assert!(validate(&invalid).is_err());
            invalid["devices"] = json!([]);
            validate(&invalid).unwrap();
        }
        let mut invalid = state.clone();
        invalid["devices"] = json!([state["devices"][0], state["devices"][0]]);
        assert!(validate(&invalid).is_err());
        for id in ["../device", "AA:BB:CC:DD:EE:GG", "aa:bb:cc:dd:ee:ff"] {
            let mut invalid = state.clone();
            invalid["devices"][0]["address"] = json!(id);
            assert!(validate(&invalid).is_err());
        }
        let mut invalid = state.clone();
        invalid["devices"][0]["name"] = json!("x".repeat(257));
        assert!(validate(&invalid).is_err());
        let mut invalid = state;
        invalid["devices"][0]["profiles"] = json!(["unknown"]);
        assert!(validate(&invalid).is_err());
    }
}
