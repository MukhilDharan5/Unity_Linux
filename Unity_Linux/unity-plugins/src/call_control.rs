use crate::{validate_command, validate_event, Plugin, Spec};
use anyhow::Result;
use serde_json::Value;
pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "call_control",
            title: "Call management",
            group: "telephony",
            sensitive: true,
            blocked:
                "Requires a complete default-dialer/InCallService implementation; not implemented",
            actions: &["answer", "hangup"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)
    }
    fn event(&self, data: &Value) -> Result<()> {
        validate_event(self.spec().id, data)
    }
}
