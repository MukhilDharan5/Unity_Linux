use crate::{validate_command, Plugin, Spec};
use anyhow::{bail, Result};
use serde_json::Value;
pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "audio",
            title: "Laptop audio on phone",
            group: "media",
            sensitive: true,
            blocked: "",
            actions: &["start", "stop"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        if data.as_object().is_none_or(|data| !data.is_empty()) {
            bail!("audio routing takes no client parameters")
        }
        Ok(())
    }
    fn event(&self, _: &Value) -> Result<()> {
        bail!("audio state is owned by the laptop")
    }
}
