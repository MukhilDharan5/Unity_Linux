//! Two-way HFP audio through PipeWire-Pulse. Never capture cellular audio on Android.
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{path::Path, time::Duration};
use tokio::{process::Command, time::timeout};

async fn pactl(args: &[&str]) -> Result<String> {
    let out = timeout(
        Duration::from_secs(4),
        Command::new("pactl").args(args).kill_on_drop(true).output(),
    )
    .await
    .context("Call audio server timed out")??;
    if !out.status.success() {
        bail!(
            "Call audio failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    Ok(String::from_utf8(out.stdout)?.trim().to_owned())
}
async fn list(kind: &str) -> Result<Vec<Value>> {
    // Some pactl versions omit module indexes from JSON entirely. Short
    // listings retain them; loopback arguments contain no tabs or newlines.
    if kind == "modules" {
        return Ok(pactl(&["list", "short", "modules"])
            .await?
            .lines()
            .filter_map(|line| {
                let mut columns = line.split('\t');
                let index = columns.next()?.parse::<u64>().ok()?;
                let name = columns.next()?;
                if name != "module-loopback" {
                    return None;
                }
                Some(json!({"index":index, "name":name, "argument":columns.next().unwrap_or("")}))
            })
            .collect());
    }
    Ok(serde_json::from_str(
        &pactl(&["--format=json", "list", kind]).await?,
    )?)
}
fn number(value: &Value) -> Option<u64> {
    value.as_u64().or_else(|| value.as_str()?.parse().ok())
}
fn bt_address(v: &Value) -> Option<String> {
    v["properties"]["api.bluez5.address"]
        .as_str()
        .map(String::from)
        .or_else(|| {
            v["name"]
                .as_str()?
                .strip_prefix("bluez_card.")
                .map(|s| s.replace('_', ":"))
        })
}
fn profile(card: &Value, prefixes: &[&str]) -> Option<String> {
    let profiles = card["profiles"].as_object()?;
    let usable = |name: &str| {
        prefixes.iter().any(|p| name.starts_with(p))
            && profiles
                .get(name)
                .is_some_and(|v| v["available"] != "no" && v["available"] != false)
    };
    // Keep the user's codec when it already has the requested role.
    card["active_profile"]
        .as_str()
        .filter(|p| usable(p))
        .map(String::from)
        .or_else(|| profiles.keys().find(|p| usable(p)).cloned())
}
fn hfp_profile(card: &Value) -> Option<String> {
    profile(
        card,
        &[
            "audio-gateway",
            "headset-audio-gateway",
            "handsfree_audio_gateway",
        ],
    )
}
fn headset_profile(card: &Value) -> Option<String> {
    profile(
        card,
        &[
            "headset-head-unit",
            "headset_head_unit",
            "handsfree_head_unit",
        ],
    )
}
fn playback_profile(card: &Value) -> Option<String> {
    profile(card, &["a2dp-sink", "a2dp_sink"]).or_else(|| headset_profile(card))
}
fn endpoint(v: &Value, address: &str) -> bool {
    let properties = &v["properties"];
    ["api.bluez5.address", "device.string"].iter().any(|key| {
        properties[key]
            .as_str()
            .is_some_and(|a| a.eq_ignore_ascii_case(address))
    }) || ["bluez.path", "api.bluez5.path"].iter().any(|key| {
        properties[key]
            .as_str()
            .is_some_and(|p| p.ends_with(&format!("dev_{}", address.replace(':', "_"))))
    })
}
fn on_card(node: &Value, card: &Value) -> bool {
    number(&card["index"]).is_some() && number(&node["card"]) == number(&card["index"])
        || bt_address(card).is_some_and(|a| endpoint(node, &a))
}
fn monitor(node: &Value) -> bool {
    node["name"].as_str().unwrap_or("").ends_with(".monitor")
        || node["properties"]["device.class"] == "monitor"
        || number(&node["monitor_of_sink"]).is_some_and(|n| n != u32::MAX as u64)
}
fn bluetooth_selection(s: &str) -> Option<&str> {
    s.strip_prefix("bluetooth:")
}

#[derive(Default)]
pub(super) struct Router {
    route: Option<Route>,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
struct Node {
    name: String,
    index: u64,
}
impl Node {
    fn from(v: &Value) -> Option<Self> {
        let name = v["name"].as_str()?;
        if name.is_empty()
            || !name
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"._:-".contains(&c))
        {
            return None;
        }
        Some(Self {
            name: name.into(),
            index: number(&v["index"])?,
        })
    }
}
#[derive(Serialize, Deserialize)]
struct Link {
    module: String,
    source: Node,
    sink: Node,
}
#[derive(Serialize, Deserialize)]
struct ProfileChange {
    card: String,
    previous: String,
    applied: String,
}
#[derive(Serialize, Deserialize)]
struct Route {
    device: String,
    card: String,
    // Retained for recovery of journals from older Unity versions.
    previous: String,
    tag: String,
    modules: Vec<String>,
    microphone_module: String,
    #[serde(default)]
    address: String,
    #[serde(default)]
    output: String,
    #[serde(default)]
    microphone: String,
    #[serde(default)]
    muted: bool,
    #[serde(default)]
    profiles: Vec<ProfileChange>,
    #[serde(default)]
    playback: Option<Link>,
    #[serde(default)]
    capture: Option<Link>,
    #[serde(default)]
    stopping: bool,
    #[serde(default)]
    status: Value,
}
impl Route {
    fn save(&self, dir: &Path) -> Result<()> {
        unity_security::write_private(&dir.join("call-audio.json"), &serde_json::to_vec(self)?)
    }
    fn owns(&self, module: &Value, id: &str) -> bool {
        module["index"]
            .as_u64()
            .is_some_and(|n| n.to_string() == id)
            && module["name"] == "module-loopback"
            && module["argument"].as_str().is_some_and(|a| {
                a.split_whitespace()
                    .any(|p| p == format!("source_output_properties=media.name={}", self.tag))
            })
    }
    async fn unload(&mut self, dir: &Path, id: &str, modules: &[Value]) -> Result<()> {
        if modules.iter().any(|m| self.owns(m, id)) {
            pactl(&["unload-module", id]).await?;
        }
        self.modules.retain(|m| m != id);
        self.save(dir)
    }
    async fn set_profile(&mut self, dir: &Path, card: &Value, desired: &str) -> Result<()> {
        if card["active_profile"] == desired {
            return Ok(());
        }
        let name = card["name"].as_str().context("Missing audio card")?;
        if let Some(change) = self.profiles.iter_mut().find(|p| p.card == name) {
            change.applied = desired.into();
        } else {
            self.profiles.push(ProfileChange {
                card: name.into(),
                previous: card["active_profile"]
                    .as_str()
                    .context("Missing audio profile")?
                    .into(),
                applied: desired.into(),
            });
        }
        // Write before changing a profile, including changes during reconnection.
        self.save(dir)?;
        pactl(&["set-card-profile", name, desired]).await?;
        Ok(())
    }
    async fn restore_profiles(
        &mut self,
        dir: &Path,
        cards: &[Value],
        keep: &[String],
    ) -> Result<()> {
        let mut index = 0;
        while index < self.profiles.len() {
            let change = &self.profiles[index];
            if keep.contains(&change.card) {
                index += 1;
                continue;
            }
            let Some(card) = cards.iter().find(|c| c["name"] == change.card) else {
                // Reconnect later to restore the profile; retain the private journal.
                index += 1;
                continue;
            };
            // Do not overwrite a profile the user changed themselves.
            if card["active_profile"] == change.applied {
                pactl(&["set-card-profile", &change.card, &change.previous]).await?;
            }
            self.profiles.remove(index);
            self.save(dir)?;
        }
        Ok(())
    }
    async fn prepare_profiles(&mut self, dir: &Path) -> Result<Vec<Value>> {
        let cards = list("cards").await?;
        let phone = cards
            .iter()
            .find(|c| bt_address(c).is_some_and(|a| a.eq_ignore_ascii_case(&self.address)))
            .context("Waiting for the selected Bluetooth phone to reconnect")?;
        let phone_profile = hfp_profile(phone).context("Phone does not expose a hands-free audio gateway; enable HFP in WirePlumber and reconnect")?;
        let mut plan = vec![(phone.clone(), phone_profile)];
        for (selection, capture) in [(&self.microphone, true), (&self.output, false)] {
            let Some(address) = bluetooth_selection(selection) else {
                continue;
            };
            if address.eq_ignore_ascii_case(&self.address) {
                bail!("Choose a local speaker and microphone, not the phone");
            }
            let Some(card) = cards
                .iter()
                .find(|c| bt_address(c).is_some_and(|a| a.eq_ignore_ascii_case(address)))
            else {
                continue;
            };
            if plan.iter().any(|(c, _)| c["name"] == card["name"]) {
                continue;
            }
            let desired = if capture {
                headset_profile(card)
            } else {
                playback_profile(card)
            }
            .context(
                "Selected headphones do not expose the required playback or hands-free profile",
            )?;
            plan.push((card.clone(), desired));
        }
        let keep = plan
            .iter()
            .filter_map(|(c, _)| c["name"].as_str().map(String::from))
            .collect::<Vec<_>>();
        self.restore_profiles(dir, &cards, &keep).await?;
        for (card, desired) in plan {
            self.set_profile(dir, &card, &desired).await?;
        }
        Ok(cards)
    }
    fn local_node(
        &self,
        selection: &str,
        default: &str,
        nodes: &[Value],
        cards: &[Value],
        capture: bool,
    ) -> Option<Node> {
        let selected_card = bluetooth_selection(selection).and_then(|address| {
            cards
                .iter()
                .find(|c| bt_address(c).is_some_and(|a| a.eq_ignore_ascii_case(address)))
        });
        nodes
            .iter()
            .find(|node| {
                (!capture || !monitor(node))
                    && !cards
                        .iter()
                        .any(|c| hfp_profile(c).is_some() && on_card(node, c))
                    && if bluetooth_selection(selection).is_some() {
                        selected_card.is_some_and(|c| on_card(node, c))
                    } else {
                        node["name"]
                            == if selection.is_empty() {
                                default
                            } else {
                                selection
                            }
                    }
            })
            .and_then(Node::from)
    }
    async fn sync_link(
        &mut self,
        dir: &Path,
        target: Option<(Node, Node)>,
        capture: bool,
        modules: &[Value],
        streams: (&[Value], &[Value]),
    ) -> Result<()> {
        let link = if capture {
            &self.capture
        } else {
            &self.playback
        };
        if link.is_none() && target.is_none() {
            return Ok(());
        }
        let matches = link
            .as_ref()
            .zip(target.as_ref())
            .is_some_and(|(link, (source, sink))| {
                link.source == *source
                    && link.sink == *sink
                    && modules.iter().any(|m| self.owns(m, &link.module))
                    && streams.0.iter().any(|s| {
                        number(&s["owner_module"]).is_some_and(|n| n.to_string() == link.module)
                            && number(&s["source"]) == Some(source.index)
                    })
                    && streams.1.iter().any(|s| {
                        number(&s["owner_module"]).is_some_and(|n| n.to_string() == link.module)
                            && number(&s["sink"]) == Some(sink.index)
                    })
            });
        if matches {
            return Ok(());
        }
        let old = if capture {
            self.capture.take()
        } else {
            self.playback.take()
        };
        if let Some(old) = old {
            // Keep the link in memory until unload succeeds, so failures retry safely.
            if let Err(error) = self.unload(dir, &old.module, modules).await {
                if capture {
                    self.capture = Some(old);
                } else {
                    self.playback = Some(old);
                }
                return Err(error);
            }
        }
        if capture {
            self.microphone_module.clear();
        }
        self.save(dir)?;
        let Some((source, sink)) = target else {
            return Ok(());
        };
        let module = pactl(&[
            "load-module",
            "module-loopback",
            &format!("source={}", source.name),
            &format!("sink={}", sink.name),
            "latency_msec=40",
            "source_dont_move=true",
            "sink_dont_move=true",
            &format!("source_output_properties=media.name={}", self.tag),
            &format!("sink_input_properties=media.name={}", self.tag),
        ])
        .await?;
        if module.is_empty() || !module.bytes().all(|c| c.is_ascii_digit()) {
            bail!("Invalid audio module ID");
        }
        self.modules.push(module.clone());
        let link = Link {
            module: module.clone(),
            source,
            sink,
        };
        if capture {
            self.microphone_module = module;
            self.capture = Some(link);
        } else {
            self.playback = Some(link);
        }
        self.save(dir)
    }
    async fn sync(&mut self, dir: &Path) -> Result<Value> {
        let cards = self.prepare_profiles(dir).await?;
        let (sources, sinks, modules, source_outputs, sink_inputs) = tokio::try_join!(
            list("sources"),
            list("sinks"),
            list("modules"),
            list("source-outputs"),
            list("sink-inputs")
        )?;
        let phone = cards
            .iter()
            .find(|c| c["name"] == self.card)
            .context("Selected phone is unavailable")?;
        let phone_source = sources
            .iter()
            .find(|s| on_card(s, phone) && !monitor(s))
            .and_then(Node::from);
        let phone_sink = sinks
            .iter()
            .find(|s| on_card(s, phone))
            .and_then(Node::from);
        let speaker = if self.output.is_empty() {
            pactl(&["get-default-sink"]).await?
        } else {
            String::new()
        };
        let microphone = if self.microphone.is_empty() {
            pactl(&["get-default-source"]).await?
        } else {
            String::new()
        };
        let output = self.local_node(&self.output, &speaker, &sinks, &cards, false);
        let input = self.local_node(&self.microphone, &microphone, &sources, &cards, true);
        let playback = phone_source.clone().zip(output.clone());
        let capture = if self.muted {
            None
        } else {
            input.clone().zip(phone_sink.clone())
        };
        self.sync_link(
            dir,
            playback,
            false,
            &modules,
            (&source_outputs, &sink_inputs),
        )
        .await?;
        // A muted route has no microphone loopback. Reconnecting cannot briefly
        // expose an unmuted capture stream before a later mute command arrives.
        self.sync_link(
            dir,
            capture,
            true,
            &modules,
            (&source_outputs, &sink_inputs),
        )
        .await?;
        let error = if phone_source.is_none() || phone_sink.is_none() {
            "Waiting for phone HFP audio"
        } else if output.is_none() {
            "Waiting for the selected call output; choose an available speaker"
        } else if input.is_none() && !self.muted {
            "Waiting for the selected call microphone; choose an available microphone"
        } else {
            ""
        };
        Ok(self.snapshot(error))
    }
    fn snapshot(&self, error: &str) -> Value {
        json!({"state":if error.is_empty() {"active"} else {"waiting"}, "address":self.address,
            "output":self.output, "microphone":self.microphone, "muted":self.muted, "error":error,
            "output_name":self.playback.as_ref().map(|l| &l.sink.name),
            "microphone_name":self.capture.as_ref().map(|l| &l.source.name)})
    }
    async fn suspend(&mut self, dir: &Path) -> Result<()> {
        // Profile/device disappearance must not leave a loopback retargeted by
        // server policy to an unintended device. Retry cleanup on the next tick.
        let modules = list("modules").await?;
        // Include modules loaded immediately before a crash, even if their IDs
        // had not yet been written. Ownership requires our exact random tag.
        let ids: Vec<_> = modules
            .iter()
            .filter_map(|m| {
                let id = m["index"].as_u64()?.to_string();
                self.owns(m, &id).then_some(id)
            })
            .collect();
        for id in ids {
            self.unload(dir, &id, &modules).await?;
        }
        self.modules.clear();
        self.playback = None;
        self.capture = None;
        self.microphone_module.clear();
        self.save(dir)
    }
}
impl Router {
    pub async fn phone_profile(address: &str, computer: bool) -> Result<()> {
        let cards = list("cards").await?;
        let card=cards.iter().find(|c| bt_address(c).is_some_and(|a| a.eq_ignore_ascii_case(address)))
            .context("Phone sound card is unavailable; enable the oFono backend in WirePlumber and reconnect")?;
        let profile = if computer {
            hfp_profile(card).context("Phone hands-free profile unavailable")?
        } else {
            "off".into()
        };
        pactl(&[
            "set-card-profile",
            card["name"].as_str().context("Missing phone audio card")?,
            &profile,
        ])
        .await?;
        Ok(())
    }
    pub async fn devices() -> Result<Value> {
        let (cards, sinks, sources) =
            tokio::try_join!(list("cards"), list("sinks"), list("sources")).context(
                "Install pulseaudio-utils; pair and connect your phone for Bluetooth calls",
            )?;
        let choices = |nodes: &[Value], capture: bool| {
            let mut result = Vec::new();
            for card in &cards {
                if hfp_profile(card).is_some() {
                    continue;
                }
                let Some(address) = bt_address(card) else {
                    continue;
                };
                if if capture {
                    headset_profile(card)
                } else {
                    playback_profile(card)
                }
                .is_some()
                {
                    result.push(json!({"id":format!("bluetooth:{address}"),
                        "name":card["properties"]["device.description"].as_str().unwrap_or("Bluetooth headphones"),
                        "bluetooth":true}));
                }
            }
            for node in nodes {
                if capture && monitor(node)
                    || cards
                        .iter()
                        .any(|c| bt_address(c).is_some() && on_card(node, c))
                {
                    continue;
                }
                let Some(name) = node["name"].as_str() else {
                    continue;
                };
                result.push(json!({"id":name, "name":node["description"].as_str().unwrap_or(name), "bluetooth":false}));
            }
            result
        };
        Ok(
            json!({"devices":cards.iter().filter_map(|card| Some(json!({"address":bt_address(card)?,
            "name":card["properties"]["device.description"].as_str().unwrap_or("Bluetooth phone"),
            "profile":hfp_profile(card)?}))).collect::<Vec<_>>(),
            "outputs":choices(&sinks, false), "microphones":choices(&sources, true)}),
        )
    }
    pub async fn start(
        &mut self,
        dir: &Path,
        device: &str,
        address: &str,
        output: &str,
        microphone: &str,
        muted: bool,
    ) -> Result<Value> {
        if self.route.as_ref().is_some_and(|r| !r.stopping) {
            bail!("Call audio is already active; stop it first");
        }
        unity_plugins::call_control::address(address)?;
        let cards = list("cards").await?;
        let card = cards
            .iter()
            .find(|c| bt_address(c).is_some_and(|a| a.eq_ignore_ascii_case(address)))
            .context("Selected Bluetooth phone is unavailable")?;
        hfp_profile(card).context("Phone does not expose a hands-free audio gateway; enable HFP in WirePlumber and reconnect")?;
        if self.route.is_some() {
            self.stop(dir, "").await?;
        }
        let profiles = self.route.take().map(|r| r.profiles).unwrap_or_default();
        self.route = Some(Route {
            device: device.into(),
            card: card["name"].as_str().context("Missing audio card")?.into(),
            previous: String::new(),
            tag: format!("unity_call_{}", uuid::Uuid::new_v4().simple()),
            modules: vec![],
            microphone_module: String::new(),
            address: address.into(),
            output: output.into(),
            microphone: microphone.into(),
            muted,
            profiles,
            playback: None,
            capture: None,
            stopping: false,
            status: Value::Null,
        });
        self.route.as_ref().unwrap().save(dir)?;
        let result = async {
            for attempt in 0..20 {
                let route = self.route.as_mut().unwrap();
                let status = route.sync(dir).await?;
                route.status = status.clone();
                if status["state"] == "active" {
                    route.save(dir)?;
                    return Ok(status);
                }
                if attempt < 19 {
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
            }
            bail!(
                "{}",
                self.route.as_ref().unwrap().status["error"]
                    .as_str()
                    .unwrap_or("Call audio devices unavailable")
            );
        }
        .await;
        if let Err(error) = result {
            if let Err(cleanup) = self.stop(dir, device).await {
                return Err(error.context(format!("Call audio cleanup pending: {cleanup}")));
            }
            return Err(error);
        }
        result
    }
    pub async fn select(
        &mut self,
        dir: &Path,
        device: &str,
        output: &str,
        microphone: &str,
    ) -> Result<Value> {
        let route = self
            .route
            .as_mut()
            .filter(|r| r.device == device && !r.stopping)
            .context("No desktop call audio")?;
        route.output = output.into();
        route.microphone = microphone.into();
        route.save(dir)?;
        // Keep the selection when a device is temporarily absent. The watcher
        // retries and exposes the waiting state so it can be changed again.
        self.refresh(dir)
            .await
            .map(|(_, status)| status)
            .context("No desktop call audio")
    }
    pub async fn mute(&mut self, dir: &Path, device: &str, muted: bool) -> Result<Value> {
        let route = self
            .route
            .as_mut()
            .filter(|r| r.device == device && !r.stopping)
            .context("No desktop call audio")?;
        route.muted = muted;
        route.save(dir)?;
        if muted {
            let modules = list("modules").await?;
            if let Some(link) = &route.capture {
                route.unload(dir, &link.module.clone(), &modules).await?;
            }
            route.capture = None;
            route.microphone_module.clear();
            route.save(dir)?;
        }
        self.refresh(dir)
            .await
            .map(|(_, status)| status)
            .context("No desktop call audio")
    }
    pub async fn refresh(&mut self, dir: &Path) -> Option<(String, Value)> {
        let route = self.route.as_mut()?;
        if route.stopping {
            let device = route.device.clone();
            return Some((
                device,
                self.stop(dir, "")
                    .await
                    .unwrap_or_else(|e| json!({"state":"stopped","error":e.to_string()})),
            ));
        }
        let status = match route.sync(dir).await {
            Ok(status) => status,
            Err(error) => {
                let cleanup = route.suspend(dir).await.err();
                let message = match cleanup {
                    Some(e) => format!("{error}; audio cleanup pending: {e}"),
                    None => error.to_string(),
                };
                route.snapshot(&message)
            }
        };
        if route.status != status {
            route.status = status.clone();
            if let Err(error) = route.save(dir) {
                tracing::warn!(%error, "Call audio journal update failed");
            }
        }
        Some((route.device.clone(), status))
    }
    pub async fn stop(&mut self, dir: &Path, device: &str) -> Result<Value> {
        let Some(route) = self
            .route
            .as_mut()
            .filter(|r| device.is_empty() || r.device == device)
        else {
            return Ok(json!({"state":"stopped","muted":false,"error":""}));
        };
        route.stopping = true;
        route.save(dir)?;
        route.suspend(dir).await?;
        let cards = list("cards").await?;
        if !route.previous.is_empty() {
            if cards.iter().any(|c| c["name"] == route.card) {
                pactl(&["set-card-profile", &route.card, &route.previous]).await?;
            }
            route.previous.clear();
            route.save(dir)?;
        }
        route.restore_profiles(dir, &cards, &[]).await?;
        let pending = !route.profiles.is_empty();
        if !pending {
            std::fs::remove_file(dir.join("call-audio.json"))?;
            self.route = None;
        }
        Ok(json!({"state":"stopped", "muted":false, "restoration_pending":pending, "error":""}))
    }
    pub async fn recover(&mut self, dir: &Path) -> Result<()> {
        let journal = dir.join("call-audio.json");
        if journal.exists() {
            self.route = Some(serde_json::from_slice(&std::fs::read(journal)?)?);
            self.stop(dir, "").await?;
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_phone_gateway_profiles_and_exact_addresses_match() {
        let c = json!({"name":"bluez_card.AA_BB_CC_DD_EE_FF", "profiles":{"headset-audio-gateway":{"available":"yes"}, "headset-head-unit":{"available":"yes"}}});
        assert_eq!(bt_address(&c).unwrap(), "AA:BB:CC:DD:EE:FF");
        assert_eq!(hfp_profile(&c).unwrap(), "headset-audio-gateway");
        assert!(endpoint(
            &json!({"properties":{"api.bluez5.address":"AA:BB:CC:DD:EE:FF"}}),
            "aa:bb:cc:dd:ee:ff"
        ));
        assert!(!endpoint(
            &json!({"properties":{"api.bluez5.address":"11:22:33:44:55:66"}}),
            "AA:BB:CC:DD:EE:FF"
        ));
        assert!(
            hfp_profile(&json!({"profiles":{"headset-head-unit":{"available":"yes"}}})).is_none()
        );
    }
    #[test]
    fn headset_codec_and_monitor_detection() {
        assert_eq!(hfp_profile(&json!({"active_profile":"audio-gateway","profiles":{"audio-gateway":{"available":true}}})).as_deref(),Some("audio-gateway"));
        assert!(hfp_profile(&json!({"profiles":{"audio-gateway":{"available":false}}})).is_none());
        let card = json!({"active_profile":"headset-head-unit-msbc", "profiles":{
            "headset-head-unit":{"available":"yes"}, "headset-head-unit-msbc":{"available":"yes"},
            "a2dp-sink":{"available":"yes"}, "a2dp-sink-ldac":{"available":"no"}}});
        assert_eq!(
            headset_profile(&card).as_deref(),
            Some("headset-head-unit-msbc")
        );
        assert_eq!(playback_profile(&card).as_deref(), Some("a2dp-sink"));
        assert!(monitor(&json!({"name":"odd_name", "monitor_of_sink":1})));
        assert!(!monitor(
            &json!({"name":"mic", "monitor_of_sink":4294967295u64})
        ));
        assert_eq!(number(&json!("536870918")), Some(536870918));
        assert_eq!(number(&json!(536870918)), Some(536870918));
        assert_eq!(number(&json!("n/a")), None);
    }
}
