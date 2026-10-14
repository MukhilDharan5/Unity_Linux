//! BlueZ RFCOMM profiles. Bluetooth bonding is followed by pinned TLS and Unity authentication.
use anyhow::{bail, Context, Result};
use std::{
    collections::HashMap,
    os::unix::net::UnixStream as StdStream,
    sync::{Arc, Mutex, Weak},
};
use tokio::{
    net::UnixStream,
    sync::{mpsc, watch},
};
use zbus::{
    message::Header,
    zvariant::{OwnedFd, OwnedObjectPath, OwnedValue, Value},
};

pub const SERVICE_UUID: &str = "ab049870-6fb6-4d30-9b3c-190770271001";
pub const AUDIO_UUID: &str = "ab049870-6fb6-4d30-9b3c-190770271002";
pub const SCREEN_UUID: &str = "ab049870-6fb6-4d30-9b3c-190770271003";
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Channel {
    Control,
    Audio,
    Screen,
}
pub struct Incoming {
    pub stream: BluetoothStream,
    pub channel: Channel,
    pub device: String,
}
/// Owns the shutdown handle as long as TLS or a side-stream worker owns the FD.
pub struct BluetoothStream {
    stream: UnixStream,
    _shutdown: Arc<StdStream>,
}
impl std::os::fd::AsRawFd for BluetoothStream {
    fn as_raw_fd(&self) -> std::os::fd::RawFd {
        self.stream.as_raw_fd()
    }
}
impl tokio::io::AsyncRead for BluetoothStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.stream).poll_read(cx, buf)
    }
}
impl tokio::io::AsyncWrite for BluetoothStream {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        bytes: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        std::pin::Pin::new(&mut self.stream).poll_write(cx, bytes)
    }
    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.stream).poll_flush(cx)
    }
    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.stream).poll_shutdown(cx)
    }
}
struct Profile {
    owner: String,
    channel: Channel,
    tx: mpsc::Sender<Incoming>,
    released: watch::Sender<bool>,
    sockets: Mutex<HashMap<String, Vec<Weak<StdStream>>>>,
}
impl Profile {
    fn authorize(&self, h: &Header<'_>) -> zbus::fdo::Result<()> {
        if h.sender().is_none_or(|s| s.as_str() != self.owner) {
            return Err(zbus::fdo::Error::AccessDenied(
                "Only BlueZ may supply Bluetooth sockets".into(),
            ));
        }
        Ok(())
    }
    fn disconnect(&self, device: Option<&str>) {
        let mut sockets = self.sockets.lock().unwrap();
        if let Some(device) = device {
            if let Some(handles) = sockets.remove(device) {
                for handle in handles.into_iter().filter_map(|h| h.upgrade()) {
                    handle.shutdown(std::net::Shutdown::Both).ok();
                }
            }
        } else {
            for handles in sockets.drain().map(|(_, v)| v) {
                for handle in handles.into_iter().filter_map(|h| h.upgrade()) {
                    handle.shutdown(std::net::Shutdown::Both).ok();
                }
            }
        }
    }
}
impl Drop for Profile {
    fn drop(&mut self) {
        self.disconnect(None);
    }
}
#[zbus::interface(name = "org.bluez.Profile1")]
impl Profile {
    fn release(&self, #[zbus(header)] h: Header<'_>) -> zbus::fdo::Result<()> {
        self.authorize(&h)?;
        self.disconnect(None);
        self.released.send(true).ok();
        Ok(())
    }
    fn new_connection(
        &self,
        device: OwnedObjectPath,
        fd: OwnedFd,
        _properties: HashMap<String, OwnedValue>,
        #[zbus(header)] h: Header<'_>,
    ) -> zbus::fdo::Result<()> {
        self.authorize(&h)?;
        let result = (|| -> Result<()> {
            let raw = StdStream::from(std::os::fd::OwnedFd::from(fd));
            raw.set_nonblocking(true)?;
            let shutdown = Arc::new(raw.try_clone()?);
            let stream = BluetoothStream {
                stream: UnixStream::from_std(raw)?,
                _shutdown: shutdown.clone(),
            };
            self.tx
                .try_send(Incoming {
                    stream,
                    channel: self.channel,
                    device: device.to_string(),
                })
                .map_err(|_| anyhow::anyhow!("Bluetooth connection limit reached"))?;
            let mut sockets = self.sockets.lock().unwrap();
            // Dead handles must not accumulate over reconnects.
            sockets.retain(|_, handles| {
                handles.retain(|h| h.strong_count() > 0);
                !handles.is_empty()
            });
            sockets
                .entry(device.to_string())
                .or_default()
                .push(Arc::downgrade(&shutdown));
            Ok(())
        })();
        result.map_err(|e| zbus::fdo::Error::Failed(e.to_string()))
    }
    fn request_disconnection(
        &self,
        device: OwnedObjectPath,
        #[zbus(header)] h: Header<'_>,
    ) -> zbus::fdo::Result<()> {
        self.authorize(&h)?;
        self.disconnect(Some(device.as_str()));
        Ok(())
    }
}

pub struct Listener {
    bus: zbus::Connection,
    owner: String,
    incoming: mpsc::Receiver<Incoming>,
    released: watch::Receiver<bool>,
}
impl Listener {
    pub async fn new() -> Result<Self> {
        Self::on_bus(zbus::Connection::system().await?).await
    }
    /// Also used by the isolated BlueZ contract tests; never exposes a network listener.
    pub async fn on_bus(bus: zbus::Connection) -> Result<Self> {
        let owner = bluez_owner(&bus).await?;
        let (tx, incoming) = mpsc::channel(16);
        let (released_tx, released) = watch::channel(false);
        let proxy =
            zbus::Proxy::new(&bus, "org.bluez", "/org/bluez", "org.bluez.ProfileManager1").await?;
        for (name, uuid, channel) in [
            ("control", SERVICE_UUID, Channel::Control),
            ("audio", AUDIO_UUID, Channel::Audio),
            ("screen", SCREEN_UUID, Channel::Screen),
        ] {
            let path = OwnedObjectPath::try_from(format!("/org/unityconnect/bluetooth/{name}"))?;
            bus.object_server()
                .at(
                    &path,
                    Profile {
                        owner: owner.clone(),
                        channel,
                        tx: tx.clone(),
                        released: released_tx.clone(),
                        sockets: Mutex::new(HashMap::new()),
                    },
                )
                .await?;
            let options: HashMap<&str, Value<'_>> = HashMap::from([
                ("Name", Value::from(format!("Unity {name}"))),
                ("Role", Value::from("server")),
                ("Channel", Value::from(0u16)), // BlueZ allocates a free RFCOMM channel and SDP record.
                ("RequireAuthentication", Value::from(true)),
                ("RequireAuthorization", Value::from(false)), // Unity approval and TLS are mandatory above bonding.
            ]);
            proxy
                .call::<_, _, ()>("RegisterProfile", &(&path, uuid, options))
                .await
                .context("Register Unity Bluetooth profile")?;
        }
        drop(proxy);
        Ok(Self {
            bus,
            owner,
            incoming,
            released,
        })
    }
    pub async fn accept(&mut self) -> Result<Incoming> {
        tokio::select! {
            incoming = self.incoming.recv() => incoming.context("Bluetooth listener closed"),
            _ = self.released.changed() => bail!("BlueZ released Unity profiles"),
        }
    }
    pub async fn healthy(&self) -> bool {
        !*self.released.borrow()
            && bluez_owner(&self.bus)
                .await
                .is_ok_and(|owner| owner == self.owner)
    }
    pub async fn info(&self) -> Result<serde_json::Value> {
        info(&self.bus).await
    }
}
async fn bluez_owner(bus: &zbus::Connection) -> Result<String> {
    Ok(zbus::fdo::DBusProxy::new(bus)
        .await?
        .get_name_owner(zbus::names::BusName::try_from("org.bluez")?)
        .await?
        .to_string())
}
pub async fn info(bus: &zbus::Connection) -> Result<serde_json::Value> {
    let proxy =
        zbus::Proxy::new(bus, "org.bluez", "/", "org.freedesktop.DBus.ObjectManager").await?;
    let objects: HashMap<OwnedObjectPath, HashMap<String, HashMap<String, OwnedValue>>> =
        proxy.call("GetManagedObjects", &()).await?;
    let adapters: Vec<_> = objects.into_iter().filter_map(|(path, mut ifs)| {
        let properties = ifs.remove("org.bluez.Adapter1")?;
        Some(serde_json::json!({"path":path.as_str(),
            "address":properties.get("Address").and_then(|v| <&str>::try_from(v).ok()).unwrap_or(""),
            "name":properties.get("Alias").and_then(|v| <&str>::try_from(v).ok()).unwrap_or("Bluetooth"),
            "powered":properties.get("Powered").and_then(|v| bool::try_from(v).ok()).unwrap_or(false)}))
    }).collect();
    Ok(
        serde_json::json!({"status":"ready","transport":"rfcomm-tls","adapters":adapters,"service_uuid":SERVICE_UUID}),
    )
}
pub async fn adapters() -> Result<Vec<String>> {
    let value = info(&zbus::Connection::system().await?).await?;
    Ok(value["adapters"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|a| a["path"].as_str().map(str::to_owned))
        .collect())
}
