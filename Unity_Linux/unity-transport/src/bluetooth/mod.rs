//! BlueZ adapter inspection. No GATT data channel is advertised until authenticated framing exists.
use anyhow::Result;
use std::collections::HashMap;
use zbus::zvariant::{OwnedObjectPath, OwnedValue};
pub const SERVICE_UUID: &str = "ab049870-6fb6-4d30-9b3c-190770271001";
pub async fn adapters() -> Result<Vec<String>> {
    let bus = zbus::Connection::system().await?;
    let proxy =
        zbus::Proxy::new(&bus, "org.bluez", "/", "org.freedesktop.DBus.ObjectManager").await?;
    let objects: HashMap<OwnedObjectPath, HashMap<String, HashMap<String, OwnedValue>>> =
        proxy.call("GetManagedObjects", &()).await?;
    Ok(objects
        .into_iter()
        .filter(|(_, ifs)| ifs.contains_key("org.bluez.Adapter1"))
        .map(|(path, _)| path.to_string())
        .collect())
}
