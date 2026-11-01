//! Versioned public session-bus API. No frontend dependencies or transport handles.
use anyhow::Result;
use futures_lite::StreamExt;
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    sync::{Arc, OnceLock},
};
use tokio::sync::Mutex;
use unity_core::Engine;
use zbus::{interface, message::Header, Connection};
pub const BUS: &str = "org.unityconnect";
pub const ROOT: &str = "/org/unityconnect/Manager";
pub fn device_path(id: &str) -> String {
    format!("/org/unityconnect/devices/d_{id}")
}
pub fn operation_path(id: &str) -> String {
    format!("/org/unityconnect/operations/o_{id}")
}
#[derive(Debug, zbus::DBusError)]
#[zbus(prefix = "org.unityconnect.Error")]
pub enum Error {
    NotAuthorized(String),
    InvalidArgument(String),
    Disconnected(String),
    Unsupported(String),
    Failed(String),
    #[zbus(error)]
    ZBus(zbus::Error),
}

fn err(e: anyhow::Error) -> Error {
    let s = e.to_string();
    if s.contains("disconnected") {
        Error::Disconnected(s)
    } else if s.contains("unsupported") || s.contains("unavailable") || s.contains("disabled") {
        Error::Unsupported(s)
    } else if s.contains("belongs to another client") {
        Error::NotAuthorized(s)
    } else if [
        "unknown",
        "missing",
        "invalid",
        "outside allowed",
        "only http",
        "empty",
        "must be 1",
    ]
    .iter()
    .any(|word| s.contains(word))
    {
        Error::InvalidArgument(s)
    } else {
        Error::Failed(s)
    }
}
fn sender(h: &Header<'_>) -> Result<String, Error> {
    h.sender()
        .map(|s| s.to_string())
        .ok_or_else(|| Error::NotAuthorized("Missing D-Bus sender".into()))
}
#[derive(Clone)]
struct Grant {
    name: String,
    scopes: Vec<String>,
    approved: bool,
}
fn requested_grant(previous: Option<&Grant>, name: &str, scopes: Vec<String>) -> Grant {
    let approved = previous.is_some_and(|g| {
        g.approved && g.name == name && scopes.iter().all(|scope| g.scopes.contains(scope))
    });
    Grant {
        name: name.into(),
        scopes,
        approved,
    }
}
struct Access {
    bus: OnceLock<Connection>,
    clients: Mutex<BTreeMap<String, Grant>>,
    control_exe: std::path::PathBuf,
}
impl Access {
    fn new() -> Result<Arc<Self>> {
        let path = std::env::current_exe()?
            .parent()
            .ok_or_else(|| anyhow::anyhow!("missing executable directory"))?
            .join("unityctl");
        Ok(Arc::new(Self {
            bus: OnceLock::new(),
            clients: Mutex::new(BTreeMap::new()),
            control_exe: path,
        }))
    }
    fn bus(&self) -> &Connection {
        self.bus.get().expect("D-Bus initialized")
    }
    async fn trusted(&self, name: &str) -> Result<bool, Error> {
        let proxy = zbus::fdo::DBusProxy::new(self.bus()).await?;
        let credentials = proxy
            .get_connection_credentials(
                zbus::names::BusName::try_from(name)
                    .map_err(|e| Error::InvalidArgument(e.to_string()))?,
            )
            .await
            .map_err(|e| Error::NotAuthorized(e.to_string()))?;
        if credentials.unix_user_id() != Some(unsafe { libc::geteuid() }) {
            return Ok(false);
        }
        let Some(pid) = credentials.process_id() else {
            return Ok(false);
        };
        let exe = std::fs::read_link(format!("/proc/{pid}/exe"))
            .map_err(|e| Error::NotAuthorized(e.to_string()))?;
        let expected = match self.control_exe.canonicalize() {
            Ok(p) => p,
            Err(_) => return Ok(false),
        };
        if exe != expected {
            return Ok(false);
        }
        use std::os::unix::fs::MetadataExt;
        let m = std::fs::metadata(&expected).map_err(|e| Error::NotAuthorized(e.to_string()))?;
        // Installed /usr/bin unityctl is root-owned; local builds are owned by this user.
        Ok((m.uid() == 0 || m.uid() == unsafe { libc::geteuid() }) && m.mode() & 0o022 == 0)
    }
    async fn require(&self, h: &Header<'_>, scope: &str) -> Result<String, Error> {
        let name = sender(h)?;
        if self.trusted(&name).await? {
            return Ok(name);
        };
        if self
            .clients
            .lock()
            .await
            .get(&name)
            .is_some_and(|g| g.approved && g.scopes.iter().any(|s| s == scope))
        {
            return Ok(name);
        }
        Err(Error::NotAuthorized(format!(
            "Request access and approve this client with unityctl authorize {name}"
        )))
    }
}
#[derive(Clone)]
struct Context {
    engine: Arc<Engine>,
    access: Arc<Access>,
    id: String,
}
impl Context {
    async fn snapshot(&self, h: &Header<'_>, feature: Option<&str>) -> Result<String, Error> {
        self.access.require(h, "read").await?;
        let d = self.engine.device(&self.id).await.map_err(err)?;
        if !d.connected {
            return Err(Error::Disconnected(
                "Phone is disconnected; live state has been cleared".into(),
            ));
        }
        if let Some(feature) = feature {
            serde_json::to_string(&d.state.get(feature).cloned().unwrap_or(Value::Null))
        } else {
            serde_json::to_string(&d)
        }
        .map_err(|e| Error::Failed(e.to_string()))
    }
    async fn execute(
        &self,
        h: &Header<'_>,
        feature: &str,
        action: &str,
        payload: &str,
    ) -> Result<String, Error> {
        let owner = self.access.require(h, "control").await?;
        if payload.len() > unity_protocol::MAX_TEXT {
            return Err(Error::InvalidArgument("Payload exceeds 64 KiB".into()));
        }
        let data =
            serde_json::from_str(payload).map_err(|e| Error::InvalidArgument(e.to_string()))?;
        let id = self
            .engine
            .execute(&self.id, feature, action, data, &owner)
            .await
            .map_err(err)?;
        let path = operation_path(&id);
        self.access
            .bus()
            .object_server()
            .at(
                path.clone(),
                Operation {
                    engine: self.engine.clone(),
                    access: self.access.clone(),
                    id,
                },
            )
            .await?;
        Ok(path)
    }
}
struct Manager {
    engine: Arc<Engine>,
    access: Arc<Access>,
}
#[interface(name = "org.unityconnect.Manager1")]
impl Manager {
    #[zbus(property)]
    fn api_version(&self) -> u32 {
        1
    }
    async fn list_devices(&self) -> Vec<zbus::zvariant::OwnedObjectPath> {
        self.engine
            .devices()
            .await
            .iter()
            .filter_map(|d| zbus::zvariant::OwnedObjectPath::try_from(device_path(&d.id)).ok())
            .collect()
    }
    async fn start_discovery(
        &self,
        seconds: u32,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<(), Error> {
        self.access.require(&h, "control").await?;
        self.engine.discover(seconds).await.map_err(err)
    }
    async fn pairing_info(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.access.require(&h, "control").await?;
        Ok(json!({"fingerprint":self.engine.identity.fingerprint,"port":self.engine.port,"pending":self.engine.pending_pairs().await,"bluetooth":self.engine.bluetooth_info().await,"network":self.engine.network_info().await}).to_string())
    }
    async fn firefox_status(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.access.require(&h, "read").await?;
        Ok(self.engine.firefox_status().await.to_string())
    }
    async fn set_firefox_enabled(
        &self,
        enabled: bool,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<(), Error> {
        self.access.require(&h, "control").await?;
        self.engine.set_firefox_enabled(enabled).await.map_err(err)
    }
    async fn restore_firefox_context(
        &self,
        profile_id: &str,
        context_id: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.access.require(&h, "control").await?;
        self.engine
            .restore_firefox_context(profile_id, context_id)
            .await
            .map_err(err)
    }
    async fn approve_pairing(
        &self,
        device_id: &str,
        approve: bool,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<(), Error> {
        self.access.require(&h, "control").await?;
        self.engine
            .approve_pair(device_id, approve)
            .await
            .map_err(err)
    }
    async fn unpair(&self, device_id: &str, #[zbus(header)] h: Header<'_>) -> Result<(), Error> {
        self.access.require(&h, "control").await?;
        self.engine.unpair(device_id).await.map_err(err)
    }
    async fn request_access(
        &self,
        name: &str,
        scopes: Vec<String>,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        if name.is_empty()
            || name.len() > 128
            || scopes.is_empty()
            || scopes
                .iter()
                .any(|s| !matches!(s.as_str(), "read" | "control"))
        {
            return Err(Error::InvalidArgument(
                "Invalid name or scopes (read, control)".into(),
            ));
        }
        let client = sender(&h)?;
        let mut clients = self.access.clients.lock().await;
        if clients.len() >= 128 && !clients.contains_key(&client) {
            return Err(Error::Failed("Client request limit reached".into()));
        };
        let grant = requested_grant(clients.get(&client), name, scopes);
        clients.insert(client.clone(), grant);
        drop(clients);
        self.access
            .bus()
            .emit_signal(
                None::<&str>,
                ROOT,
                "org.unityconnect.Manager1",
                "AccessRequested",
                &(&client,),
            )
            .await?;
        Ok(client)
    }
    async fn get_access_status(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        let client = sender(&h)?;
        let clients = self.access.clients.lock().await;
        Ok(match clients.get(&client) {
            Some(g) => json!({"requested":true,"approved":g.approved,"scopes":g.scopes}),
            None => json!({"requested":false,"approved":false,"scopes":[]}),
        }
        .to_string())
    }
    async fn list_access_requests(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        if !self.access.trusted(&sender(&h)?).await? {
            return Err(Error::NotAuthorized(
                "Only the trusted unityctl executable can approve clients".into(),
            ));
        }
        Ok(json!(self.access.clients.lock().await.iter().map(|(bus,g)|json!({"bus_name":bus,"name":g.name,"scopes":g.scopes,"approved":g.approved})).collect::<Vec<_>>()).to_string())
    }
    async fn approve_access(
        &self,
        client: &str,
        approve: bool,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<(), Error> {
        if !self.access.trusted(&sender(&h)?).await? {
            return Err(Error::NotAuthorized(
                "Only the trusted unityctl executable can approve clients".into(),
            ));
        }
        let mut clients = self.access.clients.lock().await;
        let g = clients
            .get_mut(client)
            .ok_or_else(|| Error::InvalidArgument("Unknown client request".into()))?;
        g.approved = approve;
        drop(clients);
        self.engine.changed.send(String::new()).ok();
        Ok(())
    }
    async fn diagnostics(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.access.require(&h, "read").await?;
        let devices = self.engine.devices().await;
        Ok(json!({"api_version":1,"protocol_version":1,"devices":devices.iter().map(|d|json!({"id":d.id,"connected":d.connected,"last_error":d.last_error})).collect::<Vec<_>>(),"bluetooth":self.engine.bluetooth_info().await,"lan":"TLS 1.3 with certificate pinning and device challenge authentication","network":self.engine.network_info().await,"pairing":"explicit 120-second maximum window"}).to_string())
    }
    async fn list_operations(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        let owner = self.access.require(&h, "read").await?;
        serde_json::to_string(&self.engine.operations(&owner).await)
            .map_err(|e| Error::Failed(e.to_string()))
    }
    #[zbus(signal)]
    async fn access_requested(
        emitter: &zbus::object_server::SignalEmitter<'_>,
        client: &str,
    ) -> zbus::Result<()>;
    #[zbus(signal)]
    async fn changed(
        emitter: &zbus::object_server::SignalEmitter<'_>,
        object_path: &str,
    ) -> zbus::Result<()>;
}

#[cfg(test)]
mod access_tests {
    use super::*;
    #[test]
    fn repeat_requests_preserve_approval_without_escalating_scopes() {
        let grant = Grant {
            name: "Client".into(),
            scopes: vec!["read".into()],
            approved: true,
        };
        assert!(requested_grant(Some(&grant), "Client", vec!["read".into()]).approved);
        assert!(
            !requested_grant(
                Some(&grant),
                "Client",
                vec!["read".into(), "control".into()]
            )
            .approved
        );
        assert!(!requested_grant(Some(&grant), "Other", vec!["read".into()]).approved);
        assert!(!requested_grant(None, "Client", vec!["read".into()]).approved);
        let revoked = Grant {
            approved: false,
            ..grant
        };
        assert!(!requested_grant(Some(&revoked), "Client", vec!["read".into()]).approved);
    }
}
struct Device(Context);
#[interface(name = "org.unityconnect.Device1")]
impl Device {
    #[zbus(property)]
    fn id(&self) -> &str {
        &self.0.id
    }
    #[zbus(property)]
    async fn name(&self) -> String {
        self.0
            .engine
            .device(&self.0.id)
            .await
            .map(|d| d.name)
            .unwrap_or_default()
    }
    #[zbus(property)]
    async fn connected(&self) -> bool {
        self.0
            .engine
            .device(&self.0.id)
            .await
            .is_ok_and(|d| d.connected)
    }
    #[zbus(property)]
    async fn capabilities_json(&self) -> String {
        self.0
            .engine
            .device(&self.0.id)
            .await
            .map(|d| {
                let mut caps = d.capabilities;
                for c in &mut caps {
                    if d.disabled.contains(&c.id) {
                        c.status = unity_protocol::Availability::Disabled;
                        c.reason = "Disabled in Unity settings".into()
                    }
                }
                serde_json::to_string(&caps).unwrap_or_default()
            })
            .unwrap_or_else(|_| "[]".into())
    }
    async fn get_state(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.snapshot(&h, None).await
    }
    async fn execute(
        &self,
        feature: &str,
        action: &str,
        payload_json: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0.execute(&h, feature, action, payload_json).await
    }
    async fn disconnect(&self, #[zbus(header)] h: Header<'_>) -> Result<(), Error> {
        self.0.access.require(&h, "control").await?;
        self.0.engine.disconnect(&self.0.id).await.map_err(err)
    }
    async fn set_feature_enabled(
        &self,
        feature: &str,
        enabled: bool,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<(), Error> {
        self.0.access.require(&h, "control").await?;
        self.0
            .engine
            .set_enabled(&self.0.id, feature, enabled)
            .await
            .map_err(err)
    }
    #[zbus(signal)]
    async fn state_changed(emitter: &zbus::object_server::SignalEmitter<'_>) -> zbus::Result<()>;
}
struct Operation {
    engine: Arc<Engine>,
    access: Arc<Access>,
    id: String,
}
#[interface(name = "org.unityconnect.Operation1")]
impl Operation {
    async fn get_state(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        let owner = self.access.require(&h, "read").await?;
        serde_json::to_string(&self.engine.operation(&self.id, &owner).await.map_err(err)?)
            .map_err(|e| Error::Failed(e.to_string()))
    }
    async fn cancel(&self, #[zbus(header)] h: Header<'_>) -> Result<(), Error> {
        let owner = self.access.require(&h, "control").await?;
        self.engine.cancel(&self.id, &owner).await.map_err(err)
    }
    #[zbus(signal)]
    async fn changed(emitter: &zbus::object_server::SignalEmitter<'_>) -> zbus::Result<()>;
}
struct Notification(Context);
#[interface(name = "org.unityconnect.Notification1")]
impl Notification {
    async fn get_snapshot(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.snapshot(&h, Some("notifications")).await
    }
    async fn dismiss(&self, id: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "notifications",
                "dismiss",
                &json!({"id":id}).to_string(),
            )
            .await
    }
    async fn reply(
        &self,
        id: &str,
        action: &str,
        text: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "notification_actions",
                "reply",
                &json!({"id":id,"action":action,"text":text}).to_string(),
            )
            .await
    }
    async fn invoke(
        &self,
        id: &str,
        action: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "notification_actions",
                "invoke",
                &json!({"id":id,"action":action}).to_string(),
            )
            .await
    }
}
struct Clipboard(Context);
#[interface(name = "org.unityconnect.Clipboard1")]
impl Clipboard {
    async fn get_text(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.snapshot(&h, Some("clipboard")).await
    }
    async fn set_text(&self, text: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(&h, "clipboard", "set", &json!({"text":text}).to_string())
            .await
    }
}
struct Transfer(Context);
#[interface(name = "org.unityconnect.Transfer1")]
impl Transfer {
    async fn send_file(&self, path: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(&h, "files", "send", &json!({"path":path}).to_string())
            .await
    }
    async fn get_snapshot(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.snapshot(&h, Some("files")).await
    }
}
struct Media(Context);
#[interface(name = "org.unityconnect.Media1")]
impl Media {
    async fn control(
        &self,
        action: &str,
        position_ms: u64,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "media",
                action,
                &json!({"position":position_ms}).to_string(),
            )
            .await
    }
    async fn get_snapshot(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.snapshot(&h, Some("media")).await
    }
}
struct Telephony(Context);
#[interface(name = "org.unityconnect.Telephony1")]
impl Telephony {
    async fn list_conversation(
        &self,
        address: &str,
        offset: u32,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "sms",
                "list",
                &json!({"address":address,"offset":offset}).to_string(),
            )
            .await
    }
    async fn dial(&self, number: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "dial",
                &json!({"number":number}).to_string(),
            )
            .await
    }
    async fn list_contacts(
        &self,
        query: &str,
        offset: u32,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "contacts",
                &json!({"query":query,"offset":offset}).to_string(),
            )
            .await
    }
    async fn list_recent_calls(
        &self,
        offset: u32,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "history",
                &json!({"offset":offset}).to_string(),
            )
            .await
    }
    async fn control_call_by_id(
        &self,
        action: &str,
        call_id: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                action,
                &json!({"call_id":call_id}).to_string(),
            )
            .await
    }
    async fn send_tone(
        &self,
        call_id: &str,
        tone: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "dtmf",
                &json!({"call_id":call_id,"tone":tone}).to_string(),
            )
            .await
    }
    async fn set_muted(&self, muted: bool, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "mute",
                &json!({"muted":muted}).to_string(),
            )
            .await
    }
    async fn set_audio_route(
        &self,
        route: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "route",
                &json!({"route":route}).to_string(),
            )
            .await
    }
    async fn list_audio_devices(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(&h, "call_control", "audio_devices", "{}")
            .await
    }
    async fn start_desktop_audio(
        &self,
        address: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "audio_start",
                &json!({"address":address}).to_string(),
            )
            .await
    }
    async fn stop_desktop_audio(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.execute(&h, "call_control", "audio_stop", "{}").await
    }
    async fn select_desktop_audio_devices(
        &self,
        output: &str,
        microphone: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "call_control",
                "audio_select",
                &json!({"output":output,"microphone":microphone}).to_string(),
            )
            .await
    }
    async fn list_messages(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.execute(&h, "sms", "list", "{}").await
    }
    async fn send_sms(
        &self,
        address: &str,
        body: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "sms",
                "send",
                &json!({"address":address,"body":body}).to_string(),
            )
            .await
    }
    async fn control_call(
        &self,
        action: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0.execute(&h, "call_control", action, "{}").await
    }
    async fn get_snapshot(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.access.require(&h, "read").await?;
        let device = self.0.engine.device(&self.0.id).await.map_err(err)?;
        if !device.connected {
            return Err(Error::Disconnected("Phone is disconnected".into()));
        }
        let mut snapshot = device
            .state
            .get("call_notifications")
            .filter(|v| v.is_object())
            .cloned()
            .unwrap_or_else(|| json!({}));
        for feature in ["call_notifications", "call_control", "call_audio", "sms"] {
            snapshot[feature] = device.state.get(feature).cloned().unwrap_or(Value::Null);
        }
        Ok(snapshot.to_string())
    }
}
struct RemoteInput(Context);
#[interface(name = "org.unityconnect.RemoteInput1")]
impl RemoteInput {
    async fn text(&self, text: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "remote_input",
                "text",
                &json!({"text":text}).to_string(),
            )
            .await
    }
    async fn gesture(
        &self,
        action: &str,
        x: f64,
        y: f64,
        end_x: f64,
        end_y: f64,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "remote_input",
                action,
                &json!({"x":x,"y":y,"end_x":end_x,"end_y":end_y}).to_string(),
            )
            .await
    }
}
struct Sharing(Context);
#[interface(name = "org.unityconnect.Sharing1")]
impl Sharing {
    async fn share_url(&self, url: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(&h, "urls", "open", &json!({"url":url}).to_string())
            .await
    }
    async fn list_photos(&self, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0.execute(&h, "photos", "list", "{}").await
    }
    async fn get_photo_preview(
        &self,
        id: &str,
        edge: u32,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        self.0
            .execute(
                &h,
                "photos",
                "preview",
                &json!({"id":id,"edge":edge}).to_string(),
            )
            .await
    }
    async fn list_files(&self, id: &str, #[zbus(header)] h: Header<'_>) -> Result<String, Error> {
        self.0
            .execute(&h, "browsing", "list", &json!({"id":id}).to_string())
            .await
    }
    async fn get_file(
        &self,
        feature: &str,
        id: &str,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<String, Error> {
        if !matches!(feature, "photos" | "browsing") {
            return Err(Error::InvalidArgument("Expected photos or browsing".into()));
        };
        self.0
            .execute(&h, feature, "get", &json!({"id":id}).to_string())
            .await
    }
}
struct Screen(Context);
#[interface(name = "org.unityconnect.Screen1")]
impl Screen {
    #[zbus(signal)]
    async fn frame_available(
        emitter: &zbus::object_server::SignalEmitter<'_>,
        sequence: u64,
    ) -> zbus::Result<()>;
    async fn get_frame(
        &self,
        #[zbus(header)] h: Header<'_>,
    ) -> Result<(u64, u32, u32, Vec<u8>), Error> {
        self.0.access.require(&h, "read").await?;
        let frame = self.0.engine.screen_frame(&self.0.id).await.map_err(err)?;
        Ok((frame.sequence, frame.width, frame.height, frame.jpeg))
    }
}
async fn register_device(engine: Arc<Engine>, access: Arc<Access>, id: String) -> Result<()> {
    let path = device_path(&id);
    let c = Context {
        engine,
        access: access.clone(),
        id,
    };
    let server = access.bus().object_server();
    server.at(path.clone(), Device(c.clone())).await?;
    server.at(path.clone(), Notification(c.clone())).await?;
    server.at(path.clone(), Clipboard(c.clone())).await?;
    server.at(path.clone(), Transfer(c.clone())).await?;
    server.at(path.clone(), Media(c.clone())).await?;
    server.at(path.clone(), Telephony(c.clone())).await?;
    server.at(path.clone(), RemoteInput(c.clone())).await?;
    server.at(path.clone(), Screen(c.clone())).await?;
    server.at(path, Sharing(c)).await?;
    Ok(())
}
pub async fn serve(engine: Arc<Engine>) -> Result<Connection> {
    let access = Access::new()?;
    let bus = zbus::connection::Builder::session()?
        .name(BUS)?
        .serve_at(
            ROOT,
            Manager {
                engine: engine.clone(),
                access: access.clone(),
            },
        )?
        .build()
        .await?;
    access
        .bus
        .set(bus.clone())
        .map_err(|_| anyhow::anyhow!("bus already initialized"))?;
    for d in engine.devices().await {
        register_device(engine.clone(), access.clone(), d.id).await?;
    }
    let cleanup = access.clone();
    tokio::spawn(async move {
        if let Ok(proxy) = zbus::fdo::DBusProxy::new(cleanup.bus()).await {
            if let Ok(mut stream) = proxy.receive_name_owner_changed().await {
                while let Some(change) = stream.next().await {
                    if let Ok(args) = change.args() {
                        if args.name().as_str().starts_with(':') && args.new_owner().is_none() {
                            cleanup.clients.lock().await.remove(args.name().as_str());
                        }
                    }
                }
            }
        }
    });
    let connection = bus.clone();
    let mut frames = engine.screen_changed.subscribe();
    let frame_bus = bus.clone();
    tokio::spawn(async move {
        loop {
            match frames.recv().await {
                Ok((id, sequence)) => {
                    frame_bus
                        .emit_signal(
                            None::<&str>,
                            device_path(&id).as_str(),
                            "org.unityconnect.Screen1",
                            "FrameAvailable",
                            &(sequence,),
                        )
                        .await
                        .ok();
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            }
        }
    });
    let mut changes = engine.changed.subscribe();
    tokio::spawn(async move {
        loop {
            match changes.recv().await {
                Ok(id) => {
                    let path = if let Some(op) = id.strip_prefix("remove_operation:") {
                        let p = operation_path(op);
                        connection
                            .object_server()
                            .remove::<Operation, _>(p.as_str())
                            .await
                            .ok();
                        p
                    } else if let Some(op) = id.strip_prefix("operation:") {
                        let p = operation_path(op);
                        connection
                            .emit_signal(
                                None::<&str>,
                                p.as_str(),
                                "org.unityconnect.Operation1",
                                "Changed",
                                &(),
                            )
                            .await
                            .ok();
                        p
                    } else if id.is_empty() {
                        ROOT.into()
                    } else {
                        if engine.device(&id).await.is_ok() {
                            if let Err(e) =
                                register_device(engine.clone(), access.clone(), id.clone()).await
                            {
                                tracing::warn!(error=%e,"register device failed")
                            }
                        }
                        let p = device_path(&id);
                        if engine.device(&id).await.is_err() {
                            let server = connection.object_server();
                            server.remove::<Device, _>(p.as_str()).await.ok();
                            server.remove::<Notification, _>(p.as_str()).await.ok();
                            server.remove::<Clipboard, _>(p.as_str()).await.ok();
                            server.remove::<Transfer, _>(p.as_str()).await.ok();
                            server.remove::<Media, _>(p.as_str()).await.ok();
                            server.remove::<Telephony, _>(p.as_str()).await.ok();
                            server.remove::<RemoteInput, _>(p.as_str()).await.ok();
                            server.remove::<Sharing, _>(p.as_str()).await.ok();
                            server.remove::<Screen, _>(p.as_str()).await.ok();
                        }
                        connection.emit_signal(None::<&str>,p.as_str(),"org.freedesktop.DBus.Properties","PropertiesChanged",&("org.unityconnect.Device1",std::collections::HashMap::<String,zbus::zvariant::Value<'_>>::new(),vec!["Name","Connected","CapabilitiesJson"])).await.ok();
                        connection
                            .emit_signal(
                                None::<&str>,
                                p.as_str(),
                                "org.unityconnect.Device1",
                                "StateChanged",
                                &(),
                            )
                            .await
                            .ok();
                        p
                    };
                    connection
                        .emit_signal(
                            None::<&str>,
                            ROOT,
                            "org.unityconnect.Manager1",
                            "Changed",
                            &(path,),
                        )
                        .await
                        .ok();
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                    connection
                        .emit_signal(
                            None::<&str>,
                            ROOT,
                            "org.unityconnect.Manager1",
                            "Changed",
                            &(ROOT,),
                        )
                        .await
                        .ok();
                }
                Err(_) => break,
            }
        }
    });
    Ok(bus)
}
