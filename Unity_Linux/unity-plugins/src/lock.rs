use crate::{validate_command, Plugin, Spec};
use anyhow::{bail, Result};
use serde_json::Value;
pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "lock",
            title: "Remote device locking",
            group: "remote",
            sensitive: true,
            blocked: "",
            actions: &["lock"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        if !data.as_object().is_some_and(|v| v.is_empty()) {
            bail!("invalid lock parameters")
        }
        Ok(())
    }
    fn event(&self, _: &Value) -> Result<()> {
        bail!("lock does not accept events")
    }
}
