use crate::{validate_command, Plugin, Spec};
use anyhow::{bail, Result};
use serde_json::Value;
pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "audio",
            title: "Phone and laptop audio",
            group: "media",
            sensitive: true,
            blocked: "",
            actions: &["start", "stop"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        let object = data
            .as_object()
            .ok_or_else(|| anyhow::anyhow!("Expected audio options"))?;
        if object
            .keys()
            .any(|key| !matches!(key.as_str(), "source" | "transport"))
            || object
                .get("source")
                .is_some_and(|v| !matches!(v.as_str(), Some("desktop" | "phone")))
            || object
                .get("transport")
                .is_some_and(|v| !matches!(v.as_str(), Some("auto" | "lan" | "bluetooth")))
            || action == "stop" && !object.is_empty()
        {
            bail!("Choose audio source desktop/phone and transport auto/lan/bluetooth")
        }
        Ok(())
    }
    fn event(&self, _: &Value) -> Result<()> {
        bail!("audio state is owned by the laptop")
    }
}
