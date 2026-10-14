use crate::{validate_command, Plugin, Spec};
use anyhow::{bail, Result};
use serde_json::Value;
pub struct Feature;
impl Plugin for Feature {
    fn spec(&self) -> Spec {
        Spec {
            id: "screen",
            title: "Screen sharing",
            group: "remote",
            sensitive: true,
            blocked: "",
            actions: &["start", "stop", "input", "display"],
        }
    }
    fn command(&self, action: &str, data: &Value) -> Result<()> {
        validate_command(self.spec(), action, data)?;
        let fields = data.as_object().unwrap();
        if action == "start" {
            if !matches!(data["source"].as_str(), Some("phone" | "desktop"))
                || fields
                    .keys()
                    .any(|k| !["source", "control", "blank_display"].contains(&k.as_str()))
            {
                bail!("screen source must be phone or desktop")
            }
            for key in ["control", "blank_display"] {
                if fields.contains_key(key) && !data[key].is_boolean() {
                    bail!("invalid screen option")
                }
            }
            if data["source"] == "phone" && fields.len() != 1
                || data["blank_display"] == true && data["control"] == false
            {
                bail!("Laptop display blanking requires desktop sharing with control")
            }
        } else if action == "input" || action == "display" {
            if !data["session_id"]
                .as_str()
                .is_some_and(unity_protocol::valid_id)
            {
                bail!("invalid screen session")
            }
            if action == "display" {
                if fields.len() != 2 || !data["on"].is_boolean() {
                    bail!("invalid display state")
                }
                return Ok(());
            }
            if !data["seq"]
                .as_u64()
                .is_some_and(|s| (1..=u32::MAX as u64).contains(&s))
            {
                bail!("invalid input sequence")
            }
            let coordinates = |keys: &[&str], low: f64, high: f64| -> Result<()> {
                if keys.iter().any(|k| {
                    !data[*k]
                        .as_f64()
                        .is_some_and(|v| v.is_finite() && (low..=high).contains(&v))
                }) {
                    bail!("invalid input coordinates")
                }
                Ok(())
            };
            let allowed: &[&str] = match data["kind"].as_str() {
                Some("pointer" | "tap" | "motion") => {
                    coordinates(&["x", "y"], 0., 1.)?;
                    if fields.contains_key("button")
                        && !matches!(data["button"].as_str(), Some("left" | "right" | "middle"))
                    {
                        bail!("invalid pointer button")
                    }
                    if data["kind"] == "pointer" && !data["pressed"].is_boolean() {
                        bail!("missing pointer state")
                    }
                    if data["kind"] == "pointer" {
                        &["x", "y", "button", "pressed"]
                    } else if data["kind"] == "tap" {
                        &["x", "y", "button"]
                    } else {
                        &["x", "y"]
                    }
                }
                Some("scroll") => {
                    coordinates(&["dx", "dy"], -1000., 1000.)?;
                    &["dx", "dy"]
                }
                Some("text") => {
                    if !data["text"].as_str().is_some_and(|t| {
                        !t.is_empty()
                            && t.len() <= 256
                            && t.chars().all(|c| !c.is_control() || "\n\t".contains(c))
                    }) {
                        bail!("invalid keyboard text")
                    }
                    &["text"]
                }
                Some("key") => {
                    let key = data["key"].as_str().unwrap_or("");
                    if ![
                        "enter",
                        "backspace",
                        "delete",
                        "tab",
                        "escape",
                        "left",
                        "right",
                        "up",
                        "down",
                        "home",
                        "end",
                        "page_up",
                        "page_down",
                    ]
                    .contains(&key)
                        && !(key.len() == 1 && key.bytes().all(|c| c.is_ascii_alphanumeric()))
                    {
                        bail!("invalid keyboard key")
                    }
                    if fields.contains_key("repeat")
                        && !data["repeat"]
                            .as_u64()
                            .is_some_and(|n| (1..=32).contains(&n))
                    {
                        bail!("invalid key repeat")
                    }
                    if let Some(modifiers) = fields.get("modifiers") {
                        let values = modifiers
                            .as_array()
                            .ok_or_else(|| anyhow::anyhow!("invalid key modifiers"))?;
                        let mut unique = std::collections::HashSet::new();
                        if values.len() > 4
                            || values.iter().any(|v| {
                                !v.as_str().is_some_and(|s| {
                                    ["ctrl", "alt", "shift", "super"].contains(&s)
                                        && unique.insert(s)
                                })
                            })
                        {
                            bail!("invalid key modifiers")
                        }
                    }
                    &["key", "modifiers", "repeat"]
                }
                _ => bail!("invalid input kind"),
            };
            if fields.keys().any(|k| {
                !["session_id", "seq", "kind"].contains(&k.as_str())
                    && !allowed.contains(&k.as_str())
            }) {
                bail!("invalid input parameter")
            }
        } else if !fields.is_empty() {
            bail!("invalid screen stop parameters")
        }
        Ok(())
    }
    fn event(&self, _: &Value) -> Result<()> {
        bail!("screen state uses the dedicated stream")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn screen_controls_validate_scope_and_bounded_input() {
        let feature = Feature;
        let valid = json!({"session_id":"ab".repeat(16),"seq":1,"kind":"tap","x":0.5,"y":0.5});
        assert!(feature.command("input", &valid).is_ok());
        for (field, value) in [
            ("session_id", json!("old session")),
            ("seq", json!(0)),
            ("x", json!(-0.01)),
            ("y", json!(1.01)),
            ("kind", json!("shell")),
            ("extra", json!(true)),
        ] {
            let mut invalid = valid.clone();
            invalid[field] = value;
            assert!(feature.command("input", &invalid).is_err());
        }
        let session = "ab".repeat(16);
        assert!(feature
            .command(
                "input",
                &json!({"session_id":session,"seq":2,"kind":"text","text":"தமிழ் 😀"})
            )
            .is_ok());
        assert!(feature
            .command(
                "input",
                &json!({"session_id":session,"seq":2,"kind":"text","text":"a".repeat(257)})
            )
            .is_err());
        assert!(feature.command("input", &json!({"session_id":session,"seq":2,"kind":"key","key":"a","modifiers":["ctrl","ctrl"]})).is_err());
        assert!(feature
            .command("display", &json!({"session_id":session,"on":false}))
            .is_ok());
        assert!(feature
            .command("display", &json!({"session_id":session,"on":0}))
            .is_err());
        assert!(feature
            .command(
                "start",
                &json!({"source":"desktop","control":false,"blank_display":true})
            )
            .is_err());
        assert!(feature
            .command("start", &json!({"source":"phone","control":true}))
            .is_err());
    }
}
