//! Unity wire protocol v1. Transport-neutral, bounded messages; no GUI dependencies.
use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
pub const VERSION: u32 = 1;
pub const MAX_FRAME: usize = 512 * 1024;
pub const MAX_TEXT: usize = 64 * 1024;
pub const PORT: u16 = 17161;
pub const DISCOVERY_PORT: u16 = 17162;
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    Available,
    PermissionRequired,
    UserActionRequired,
    Unsupported,
    Disabled,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Capability {
    pub id: String,
    pub status: Availability,
    pub reason: String,
}
impl Capability {
    pub fn available(id: &str) -> Self {
        Self {
            id: id.into(),
            status: Availability::Available,
            reason: String::new(),
        }
    }
    pub fn unsupported(id: &str, reason: &str) -> Self {
        Self {
            id: id.into(),
            status: Availability::Unsupported,
            reason: reason.into(),
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Message {
    Hello {
        version: u32,
        device_id: String,
        name: String,
        capabilities: Vec<Capability>,
    },
    Challenge {
        nonce: String,
    },
    Proof {
        mac: String,
    },
    PairPending {
        device_id: String,
    },
    PairResult {
        secret: String,
    },
    Ready,
    Event {
        feature: String,
        data: Value,
    },
    Command {
        id: String,
        feature: String,
        action: String,
        data: Value,
    },
    Result {
        id: String,
        ok: bool,
        data: Value,
        error: String,
    },
    Capabilities {
        capabilities: Vec<Capability>,
    },
    Ping,
    Pong,
}
pub fn valid_id(s: &str) -> bool {
    s.len() == 32
        && s.bytes()
            .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
}
pub fn validate_hello(
    version: u32,
    id: &str,
    name: &str,
    capabilities: &[Capability],
) -> Result<()> {
    if version != VERSION {
        bail!("unsupported protocol version")
    }
    if !valid_id(id) || name.is_empty() || name.len() > 128 || name.chars().any(char::is_control) {
        bail!("invalid device identity")
    }
    if capabilities.len() > 32
        || capabilities
            .iter()
            .any(|c| c.id.len() > 64 || c.reason.len() > 512)
    {
        bail!("invalid capabilities")
    }
    let mut ids = std::collections::HashSet::new();
    if capabilities.iter().any(|c| !ids.insert(&c.id)) {
        bail!("duplicate capability")
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_identity_and_version() {
        assert!(validate_hello(2, &"a".repeat(32), "phone", &[]).is_err());
        assert!(!valid_id("../phone"));
        assert!(validate_hello(1, &"a".repeat(32), "\n", &[]).is_err())
    }
    #[test]
    fn cross_language_fixture() {
        let m: Message =
            serde_json::from_str(include_str!("../../tests/fixtures/hello.json")).unwrap();
        assert!(matches!(m, Message::Hello { version: 1, .. }));
    }
}
