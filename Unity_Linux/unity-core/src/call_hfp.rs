//! Bluetooth HFP call control. Android retains its usual Phone app and permissions.
use crate::Engine;
use anyhow::{bail, Context, Result};
use futures_lite::StreamExt;
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    sync::watch,
    time::{interval, timeout},
};
use zbus::zvariant::{OwnedObjectPath, OwnedValue};

type Properties = BTreeMap<String, OwnedValue>;
type Objects = Vec<(OwnedObjectPath, Properties)>;
const BUS: &str = "org.ofono";
const MANAGER: &str = "org.ofono.VoiceCallManager";
fn text<'a>(properties: &'a Properties, key: &str) -> &'a str {
    properties
        .get(key)
        .and_then(|v| <&str>::try_from(v).ok())
        .unwrap_or("")
}
fn flag(properties: &Properties, key: &str) -> bool {
    properties
        .get(key)
        .and_then(|v| bool::try_from(v).ok())
        .unwrap_or(false)
}
fn bounded(value: &str, limit: usize) -> &str {
    let end = value
        .char_indices()
        .find(|(i, c)| i + c.len_utf8() > limit)
        .map(|(i, _)| i)
        .unwrap_or(value.len());
    &value[..end]
}
fn supports(properties: &Properties, interface: &str) -> bool {
    properties
        .get("Interfaces")
        .and_then(|v| Vec::<String>::try_from(v.try_clone().ok()?).ok())
        .is_some_and(|v| v.iter().any(|i| i == interface))
}
fn modem_address(path: &str, properties: &Properties) -> Option<String> {
    let serial = text(properties, "Serial").to_ascii_uppercase();
    if unity_plugins::call_control::address(&serial).is_ok() {
        return Some(serial);
    }
    // oFono omits Serial until an HFP modem is powered. Its BlueZ object
    // path still identifies the already paired phone needed for recovery.
    let address = path
        .rsplit('/')
        .next()?
        .strip_prefix("dev_")?
        .replace('_', ":")
        .to_ascii_uppercase();
    unity_plugins::call_control::address(&address)
        .ok()
        .map(|_| address)
}
fn phase(state: &str) -> Option<&str> {
    match state {
        "incoming" | "waiting" => Some("ringing"),
        "alerting" | "dialing" => Some("dialing"),
        "active" => Some("active"),
        "held" => Some("held"),
        _ => None,
    }
}
struct Call {
    id: String,
    properties: Properties,
    connected_at: u64,
}
impl Call {
    fn new(properties: Properties) -> Self {
        let mut call = Self {
            id: uuid::Uuid::new_v4().simple().to_string(),
            properties,
            connected_at: 0,
        };
        call.observe_connection();
        call
    }
    fn observe_connection(&mut self) {
        if self.connected_at == 0 && matches!(text(&self.properties, "State"), "active" | "held") {
            self.connected_at = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;
        }
    }
}
struct Modem {
    path: OwnedObjectPath,
    properties: Properties,
    calls: BTreeMap<String, Call>,
    initialized: bool,
    desktop: bool,
    muted: bool,
    retry_after: Option<Instant>,
}
#[derive(Default)]
pub(super) struct Backend {
    bus: Option<zbus::Connection>,
    modems: BTreeMap<String, Modem>,
    pub error: String,
}
impl Backend {
    async fn proxy<'a>(&'a self, path: &'a str, interface: &'a str) -> Result<zbus::Proxy<'a>> {
        let bus = self
            .bus
            .as_ref()
            .context("Install and start oFono, then enable its HFP backend in WirePlumber")?;
        Ok(zbus::Proxy::new(bus, BUS, path, interface).await?)
    }
    async fn initialize(&mut self, bus: zbus::Connection) -> Result<()> {
        self.bus = Some(bus);
        self.modems.clear();
        let manager = self.proxy("/", "org.ofono.Manager").await?;
        let modems: Objects = manager.call("GetModems", &()).await?;
        drop(manager);
        for (path, properties) in modems {
            self.add_modem(path, properties).await?;
        }
        self.error.clear();
        Ok(())
    }
    async fn add_modem(&mut self, path: OwnedObjectPath, properties: Properties) -> Result<()> {
        if text(&properties, "Type") != "hfp" {
            return Ok(());
        }
        let Some(address) = modem_address(path.as_str(), &properties) else {
            return Ok(());
        };
        self.modems.insert(
            address.clone(),
            Modem {
                path,
                properties,
                calls: BTreeMap::new(),
                initialized: false,
                desktop: false,
                muted: false,
                retry_after: None,
            },
        );
        self.initialize_calls(&address).await
    }
    async fn initialize_calls(&mut self, address: &str) -> Result<()> {
        let modem = self
            .modems
            .get(address)
            .context("Bluetooth phone unavailable")?;
        if modem.initialized || !supports(&modem.properties, MANAGER) {
            return Ok(());
        }
        let path = modem.path.clone();
        let calls: Objects = self
            .proxy(path.as_str(), MANAGER)
            .await?
            .call("GetCalls", &())
            .await?;
        let modem = self.modems.get_mut(address).unwrap();
        modem.calls = calls
            .into_iter()
            .map(|(path, properties)| (path.to_string(), Call::new(properties)))
            .collect();
        modem.initialized = true;
        Ok(())
    }
    async fn signal(&mut self, message: &zbus::Message) -> Result<()> {
        let header = message.header();
        let member = header.member().map(|v| v.as_str()).unwrap_or("");
        let interface = header.interface().map(|v| v.as_str()).unwrap_or("");
        let path = header.path().map(|v| v.as_str()).unwrap_or("");
        if interface == "org.ofono.Manager" {
            match member {
                "ModemAdded" => {
                    let (path, properties): (OwnedObjectPath, Properties) =
                        message.body().deserialize()?;
                    self.add_modem(path, properties).await?;
                }
                "ModemRemoved" => {
                    let (removed,): (OwnedObjectPath,) = message.body().deserialize()?;
                    self.modems.retain(|_, m| m.path != removed);
                }
                _ => (),
            }
            return Ok(());
        }
        let Some(address) = self
            .modems
            .iter()
            .find(|(_, m)| path == m.path.as_str() || path.starts_with(&format!("{}/", m.path)))
            .map(|(a, _)| a.clone())
        else {
            return Ok(());
        };
        let modem = self.modems.get_mut(&address).unwrap();
        match (interface, member) {
            (MANAGER, "CallAdded") => {
                let (path, properties): (OwnedObjectPath, Properties) =
                    message.body().deserialize()?;
                modem
                    .calls
                    .entry(path.to_string())
                    .or_insert_with(|| Call::new(properties));
            }
            (MANAGER, "CallRemoved") => {
                let (path,): (OwnedObjectPath,) = message.body().deserialize()?;
                modem.calls.remove(path.as_str());
            }
            ("org.ofono.VoiceCall", "PropertyChanged") => {
                let (name, value): (String, OwnedValue) = message.body().deserialize()?;
                if let Some(call) = modem
                    .calls
                    .iter_mut()
                    .find(|(p, _)| p.as_str() == path)
                    .map(|(_, c)| c)
                {
                    call.properties.insert(name, value);
                    call.observe_connection();
                }
            }
            ("org.ofono.Modem", "PropertyChanged") => {
                let (name, value): (String, OwnedValue) = message.body().deserialize()?;
                modem.properties.insert(name, value);
                if !flag(&modem.properties, "Powered") {
                    modem.retry_after = None;
                }
                if !supports(&modem.properties, MANAGER) {
                    modem.calls.clear();
                    modem.initialized = false;
                }
            }
            _ => (),
        }
        if modem.calls.is_empty() {
            modem.desktop = false;
            modem.muted = false;
        }
        self.initialize_calls(&address).await
    }
    pub fn devices(&self) -> Value {
        json!({"devices":self.modems.iter().map(|(address,m)| json!({"address":address,"name":text(&m.properties,"Name"),"connected":m.initialized})).collect::<Vec<_>>(),"error":self.error})
    }
    pub fn local_mute(&mut self, address: &str, muted: bool) {
        if let Some(modem) = self.modems.get_mut(&address.to_ascii_uppercase()) {
            modem.muted = muted;
        }
    }
    pub fn snapshot(&self, address: Option<&str>) -> Value {
        let modem = address.and_then(|a| self.modems.get(&a.to_ascii_uppercase()));
        let calls = modem
            .map(|m| {
                m.calls
                    .values()
                    .filter_map(|c| {
                        Some(
                            json!({"id":c.id,"state":phase(text(&c.properties,"State"))?,
                "number":bounded(text(&c.properties,"LineIdentification"),64),
                "name":bounded(text(&c.properties,"Name"),256), "connect_time":c.connected_at,
                "can_hold":m.calls.len()==1, "conference":flag(&c.properties,"Multiparty")}),
                        )
                    })
                    .take(16)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let reason = if !self.error.is_empty() {
            self.error.as_str()
        } else if address.is_none() {
            "Choose the paired phone for Bluetooth calls"
        } else if modem.is_none_or(|m| !m.initialized) {
            "Connect the selected phone for Bluetooth calls"
        } else {
            ""
        };
        json!({"backend":"bluetooth_hfp", "address":address, "calls":calls,
            "muted":modem.is_some_and(|m| m.muted), "route":if modem.is_some_and(|m| m.desktop) {"bluetooth"} else {"phone"},
            "routes":["phone","bluetooth"], "error":reason})
    }
    pub async fn select(&mut self, address: &str) -> Result<()> {
        let modem = self.modems.get(&address.to_ascii_uppercase()).context(
            "Selected phone is unavailable; pair it and enable Calls in Bluetooth settings",
        )?;
        if !flag(&modem.properties, "Powered") {
            self.proxy(modem.path.as_str(), "org.ofono.Modem")
                .await?
                .call::<_, _, ()>(
                    "SetProperty",
                    &("Powered", zbus::zvariant::Value::from(true)),
                )
                .await?;
        }
        Ok(())
    }
    async fn reconnect_saved(&mut self, addresses: &[String]) {
        for address in addresses {
            let Some(modem) = self.modems.get_mut(address) else {
                continue;
            };
            if flag(&modem.properties, "Powered")
                || modem.retry_after.is_some_and(|next| next > Instant::now())
            {
                continue;
            }
            modem.retry_after = Some(Instant::now() + Duration::from_secs(10));
            match timeout(Duration::from_secs(5), self.select(address)).await {
                Ok(Ok(())) => (),
                result => tracing::debug!(%address, ?result, "Bluetooth call reconnect pending"),
            }
        }
    }
    pub async fn command(&mut self, address: &str, action: &str, data: &Value) -> Result<Value> {
        let address = address.to_ascii_uppercase();
        let modem = self
            .modems
            .get(&address)
            .filter(|m| m.initialized)
            .context("Selected Bluetooth phone is disconnected")?;
        let path = modem.path.clone();
        if action == "refresh" {
            return Ok(json!({"state":"refreshed"}));
        }
        let requested = data["call_id"].as_str().unwrap_or("");
        let eligible: Vec<_> = modem
            .calls
            .iter()
            .filter(|(_, c)| {
                phase(text(&c.properties, "State")).is_some()
                    && (requested.is_empty() || c.id == requested)
                    && (action != "route"
                        || matches!(
                            phase(text(&c.properties, "State")),
                            Some("active" | "dialing")
                        ))
                    && (!matches!(action, "answer" | "reject")
                        || phase(text(&c.properties, "State")) == Some("ringing"))
            })
            .collect();
        if eligible.is_empty() || action != "route" && eligible.len() != 1 {
            bail!("Select a specific current call; this call may have ended");
        }
        let (call_path, call) = eligible[0];
        let properties: Properties = self
            .proxy(call_path.as_str(), "org.ofono.VoiceCall")
            .await?
            .call("GetProperties", &())
            .await?;
        let state = phase(text(&properties, "State")).context("The call has ended")?;
        if flag(&properties, "Emergency") {
            bail!("Manage emergency calls directly on the phone");
        }
        match action {
            "answer" | "reject" | "hangup" => {
                if matches!(action, "answer" | "reject") && state != "ringing" {
                    bail!("The call is no longer ringing");
                }
                let method = if action == "answer" {
                    "Answer"
                } else {
                    "Hangup"
                };
                self.proxy(call_path.as_str(), "org.ofono.VoiceCall")
                    .await?
                    .call::<_, _, ()>(method, &())
                    .await?;
            }
            "hold" | "unhold" => {
                if modem.calls.len() != 1
                    || state != if action == "hold" { "active" } else { "held" }
                {
                    bail!("This call cannot be held or resumed independently");
                }
                self.proxy(path.as_str(), MANAGER)
                    .await?
                    .call::<_, _, ()>("SwapCalls", &())
                    .await?;
            }
            "dtmf" => {
                if state != "active" {
                    bail!("Keypad is available during an active call");
                }
                self.proxy(path.as_str(), MANAGER)
                    .await?
                    .call::<_, _, ()>(
                        "SendTones",
                        &(data["tone"].as_str().context("Missing keypad tone")?,),
                    )
                    .await?;
            }
            "route" => {
                if !matches!(state, "active" | "dialing") {
                    bail!("No active call");
                }
                let route = data["route"].as_str().unwrap_or("");
                if matches!(route, "desktop" | "bluetooth") {
                    crate::call_audio::Router::phone_profile(&address, true).await?;
                    let cards: Objects = self
                        .proxy("/", "org.ofono.HandsfreeAudioManager")
                        .await?
                        .call("GetCards", &())
                        .await?;
                    let card = cards.iter().find(|(_,p)| text(p,"RemoteAddress").eq_ignore_ascii_case(&address) && text(p,"Type")=="handsfree")
                        .context("Phone HFP audio is unavailable; enable the oFono backend in WirePlumber")?;
                    self.proxy(card.0.as_str(), "org.ofono.HandsfreeAudioCard")
                        .await?
                        .call::<_, _, ()>("Connect", &())
                        .await?;
                    self.modems.get_mut(&address).unwrap().desktop = true;
                } else if route == "phone" {
                    crate::call_audio::Router::phone_profile(&address, false).await?;
                    self.modems.get_mut(&address).unwrap().desktop = false;
                } else {
                    bail!("Choose computer or phone audio; use the phone's call screen for its speaker and wired devices");
                }
            }
            "mute" => {
                self.proxy(path.as_str(), "org.ofono.CallVolume")
                    .await?
                    .call::<_, _, ()>(
                        "SetProperty",
                        &(
                            "Muted",
                            zbus::zvariant::Value::from(
                                data["muted"].as_bool().context("Missing mute state")?,
                            ),
                        ),
                    )
                    .await?;
                self.modems.get_mut(&address).unwrap().muted = data["muted"].as_bool().unwrap();
            }
            _ => bail!("Unsupported Bluetooth call action for {}", call.id),
        }
        Ok(json!({"state":"submitted"}))
    }
}

pub(super) async fn run(engine: Arc<Engine>, mut stop: watch::Receiver<bool>) {
    let mut retry_delay = Duration::from_secs(2);
    loop {
        let result = async {
            let bus = zbus::Connection::system().await?;
            let dbus = zbus::fdo::DBusProxy::new(&bus).await?;
            // Activation uses the installed system service, without running sudo
            // from Unity or relying on a manual service start after each failure.
            if dbus.get_name_owner(zbus::names::BusName::try_from(BUS)?).await.is_err() {
                dbus.start_service_by_name(zbus::names::WellKnownName::try_from(BUS)?, 0).await?;
            }
            let owner = dbus.get_name_owner(zbus::names::BusName::try_from(BUS)?).await?;
            let rule = zbus::MatchRule::builder().msg_type(zbus::message::Type::Signal).sender(owner.as_str())?.build();
            let mut signals = zbus::MessageStream::for_match_rule(rule,&bus,Some(128)).await?;
            drop(dbus);
            timeout(Duration::from_secs(5),engine.call_hfp.lock().await.initialize(bus)).await??;
            retry_delay = Duration::from_secs(2);
            let mut tick=interval(Duration::from_secs(1));
            loop {
                tokio::select! {
                    _=stop.changed()=>return Ok::<(),anyhow::Error>(()),
                    message=signals.next()=> {
                        let message=message.context("Bluetooth call service disconnected")??;
                        timeout(Duration::from_secs(5),engine.call_hfp.lock().await.signal(&message)).await??;
                    }
                    _=tick.tick()=> {
                        // Probe the owner so a service restart invalidates old call IDs.
                        let backend=engine.call_hfp.lock().await;
                        let conn=backend.bus.as_ref().unwrap();
                        let current=zbus::fdo::DBusProxy::new(conn).await?.get_name_owner(zbus::names::BusName::try_from(BUS)?).await?;
                        if current!=owner { bail!("Bluetooth call service restarted"); }
                    }
                }
                let addresses = {
                    let state = engine.state.lock().await;
                    state.call_phones.iter().filter(|(id, _)| state.devices.get(*id).is_some_and(|d|
                        d.connected && !d.disabled.iter().any(|f| f == "call_control")
                        && d.capabilities.iter().any(|c| c.id == "call_control" && c.status == unity_protocol::Availability::Available)
                        && d.state.get("call_control").is_some_and(|c| c["backend"] == "bluetooth_hfp")
                    )).map(|(_, address)| address.clone()).collect::<Vec<_>>()
                };
                engine.call_hfp.lock().await.reconnect_saved(&addresses).await;
                engine.publish_hfp().await;
            }
        }.await;
        if *stop.borrow() {
            break;
        }
        let reason = result
            .err()
            .map(|e| {
                tracing::warn!(%e,"Bluetooth call service unavailable; retrying automatically");
                if e.to_string().contains("ServiceUnknown") {
                    "Set up Bluetooth calls in Unity to install and enable the call service."
                        .to_owned()
                } else {
                    "Reconnecting Bluetooth call service automatically…".to_owned()
                }
            })
            .unwrap_or_default();
        {
            let mut backend = engine.call_hfp.lock().await;
            backend.modems.clear();
            backend.bus = None;
            backend.error = reason;
        }
        engine.publish_hfp().await;
        tokio::select! { _=stop.changed()=>break, _=tokio::time::sleep(retry_delay)=>() }
        retry_delay = (retry_delay * 2).min(Duration::from_secs(60));
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hfp_states_map_to_existing_call_protocol() {
        assert_eq!(phase("incoming"), Some("ringing"));
        assert_eq!(phase("waiting"), Some("ringing"));
        assert_eq!(phase("alerting"), Some("dialing"));
        assert_eq!(phase("held"), Some("held"));
        assert_eq!(phase("disconnected"), None);
    }
    #[test]
    fn unpowered_hfp_modem_can_be_identified_before_serial_is_available() {
        assert_eq!(
            modem_address(
                "/hfp/org/bluez/hci0/dev_CC_F9_F0_8D_43_C1",
                &Properties::new()
            )
            .as_deref(),
            Some("CC:F9:F0:8D:43:C1")
        );
        assert!(modem_address("/hfp/unknown", &Properties::new()).is_none());
        assert!(modem_address("/hfp/dev_NOT_A_PHONE", &Properties::new()).is_none());
    }
}
