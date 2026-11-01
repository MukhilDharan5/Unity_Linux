use crate::{validate_command, validate_event, Plugin, Spec};
use anyhow::{ensure, Result};
use serde_json::Value;

pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "samsung_routines",
            title: "Samsung Routines",
            group: "connectivity",
            sensitive: true,
            blocked: "",
            actions: &[
                "hotspot_on",
                "hotspot_off",
                "disconnect_bluetooth",
                "disconnect_bluetooth_device",
            ],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        if action == "disconnect_bluetooth_device" {
            ensure!(
                data.as_object().is_some_and(|v| v.len() == 1)
                    && data["address"]
                        .as_str()
                        .is_some_and(crate::phone_connectivity::address),
                "Choose exactly one valid Bluetooth device address"
            );
            return Ok(());
        }
        ensure!(
            data.as_object().is_some_and(|data| data.is_empty()),
            "Samsung Routines actions do not accept a payload; configure the target on the phone"
        );
        Ok(())
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
    fn only_fixed_phone_configured_actions_are_accepted() {
        for action in Feature
            .spec()
            .actions
            .iter()
            .filter(|a| **a != "disconnect_bluetooth_device")
        {
            Feature.command(action, &json!({})).unwrap();
            for data in [
                json!({"keyword": "anything"}),
                json!({"address": "AA:BB:CC:DD:EE:FF"}),
                json!({"enabled": true}),
                json!([]),
                Value::Null,
            ] {
                assert!(Feature.command(action, &data).is_err());
            }
        }
        assert!(Feature.command("toggle", &json!({})).is_err());
        Feature
            .command(
                "disconnect_bluetooth_device",
                &json!({"address":"AA:BB:CC:DD:EE:FF"}),
            )
            .unwrap();
        for payload in [
            json!({}),
            json!({"address":"../device"}),
            json!({"address":"AA:BB:CC:DD:EE:FF","keyword":"custom"}),
        ] {
            assert!(Feature
                .command("disconnect_bluetooth_device", &payload)
                .is_err());
        }
        assert!(Feature.event(&json!({"enabled": true})).is_err());
        assert!(crate::capabilities(&[])
            .iter()
            .any(|cap| cap.id == "samsung_routines" && !crate::command_available(cap)));
    }
}
