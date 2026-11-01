//! Authenticated address updates replace public-network broadcast discovery.
use super::Engine;
use anyhow::{Context, Result};
use serde_json::{json, Value};
use std::{
    collections::BTreeSet,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};
use tokio::{
    net::{TcpListener, TcpSocket},
    sync::watch,
    time::interval,
};
use unity_protocol::Message;

#[derive(Clone, Copy, Debug)]
pub(crate) struct Address {
    pub ip: IpAddr,
    pub mask: IpAddr,
    pub priority: u8,
}

pub(crate) fn addresses() -> Vec<Address> {
    let mut first = std::ptr::null_mut();
    if unsafe { libc::getifaddrs(&mut first) } != 0 {
        return Vec::new();
    }
    let mut values = Vec::new();
    let mut current = first;
    while !current.is_null() {
        let entry = unsafe { &*current };
        if entry.ifa_flags & libc::IFF_UP as u32 != 0
            && !entry.ifa_addr.is_null()
            && !entry.ifa_netmask.is_null()
        {
            let family = unsafe { (*entry.ifa_addr).sa_family as i32 };
            let physical = !entry.ifa_name.is_null()
                && std::path::Path::new("/sys/class/net")
                    .join(
                        unsafe { std::ffi::CStr::from_ptr(entry.ifa_name) }
                            .to_string_lossy()
                            .as_ref(),
                    )
                    .join("device")
                    .exists();
            let priority = if physical {
                0
            } else if entry.ifa_flags & libc::IFF_RUNNING as u32 != 0 {
                1
            } else {
                2
            };
            let pair = unsafe {
                match family {
                    libc::AF_INET => {
                        let a = &*(entry.ifa_addr as *const libc::sockaddr_in);
                        let m = &*(entry.ifa_netmask as *const libc::sockaddr_in);
                        Some(Address {
                            ip: Ipv4Addr::from(a.sin_addr.s_addr.to_ne_bytes()).into(),
                            mask: Ipv4Addr::from(m.sin_addr.s_addr.to_ne_bytes()).into(),
                            priority,
                        })
                    }
                    libc::AF_INET6 => {
                        let a = &*(entry.ifa_addr as *const libc::sockaddr_in6);
                        let m = &*(entry.ifa_netmask as *const libc::sockaddr_in6);
                        Some(Address {
                            ip: Ipv6Addr::from(a.sin6_addr.s6_addr).into(),
                            mask: Ipv6Addr::from(m.sin6_addr.s6_addr).into(),
                            priority,
                        })
                    }
                    _ => None,
                }
            };
            if let Some(pair) = pair {
                values.push(pair);
            }
        }
        current = entry.ifa_next;
    }
    unsafe {
        libc::freeifaddrs(first);
    }
    values
}

pub(crate) fn local_peer(peer: IpAddr, local: &[Address]) -> bool {
    let peer = peer.to_canonical();
    let usual = match peer {
        IpAddr::V4(ip) => {
            ip.is_private()
                || ip.is_loopback()
                || ip.is_link_local()
                || u32::from(ip) & 0xffc0_0000 == 0x6440_0000
        }
        IpAddr::V6(ip) => ip.is_unique_local() || ip.is_loopback() || ip.is_unicast_link_local(),
    };
    if usual {
        return true;
    }
    if peer.is_unspecified() || peer.is_multicast() {
        return false;
    }
    local
        .iter()
        .any(|address| match (peer, address.ip, address.mask) {
            (IpAddr::V4(p), IpAddr::V4(a), IpAddr::V4(m)) => {
                !m.is_unspecified()
                    && (u32::from(p) & u32::from(m)) == (u32::from(a) & u32::from(m))
            }
            (IpAddr::V6(p), IpAddr::V6(a), IpAddr::V6(m)) => {
                !m.is_unspecified()
                    && (u128::from(p) & u128::from(m)) == (u128::from(a) & u128::from(m))
            }
            _ => false,
        })
}

pub(crate) async fn ipv6_listener(port: u16) -> Result<TcpListener> {
    let socket = TcpSocket::new_v6()?;
    let one: libc::c_int = 1;
    let status = unsafe {
        libc::setsockopt(
            socket.as_raw_fd(),
            libc::IPPROTO_IPV6,
            libc::IPV6_V6ONLY,
            &one as *const _ as *const libc::c_void,
            std::mem::size_of_val(&one) as libc::socklen_t,
        )
    };
    if status != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    socket.set_reuseaddr(true)?;
    socket.bind((Ipv6Addr::UNSPECIFIED, port).into())?;
    Ok(socket.listen(128)?)
}

pub(crate) async fn compatible_listener(bind: &str, main_port: u16) -> Option<TcpListener> {
    // One free companion port, without changing any system firewall rule.
    if main_port != unity_protocol::PORT || bind != "0.0.0.0" {
        return None;
    }
    for port in (1714..=1764).rev() {
        if let Ok(listener) = TcpListener::bind((bind, port)).await {
            return Some(listener);
        }
    }
    None
}

impl Engine {
    pub async fn network_info(&self) -> Value {
        self.network_info_for(addresses()).await
    }
    async fn network_info_for(&self, local: Vec<Address>) -> Value {
        let ports = self.lan_ports.lock().await.clone();
        let endpoints: Vec<_> = advertised_hosts(local)
            .into_iter()
            .flat_map(|ip| {
                ports
                    .iter()
                    .map(move |port| json!({"host":ip.to_string(),"port":port}))
            })
            .collect();
        json!({"version":1,"origin":"desktop","encrypted":true,"tls":"TLSv1.3",
            "lan_endpoints":endpoints,"ports":ports,"discovery":"authenticated_link_only"})
    }
    pub(crate) async fn run_network_updates(self: Arc<Self>, mut stop: watch::Receiver<bool>) {
        let mut previous = Value::Null;
        let mut tick = interval(Duration::from_secs(5));
        loop {
            tokio::select! { _ = stop.changed() => break, _ = tick.tick() => {} }
            let local = addresses();
            let info = self.network_info_for(local.clone()).await;
            let changed = info != previous;
            previous = info.clone();
            let state = self.state.lock().await;
            for (id, links) in &state.sessions {
                let share_addresses = state
                    .devices
                    .get(id)
                    .is_some_and(|d| !d.disabled.iter().any(|f| f == "connectivity"));
                for link in &links.0 {
                    // TCP can look established long after its Wi-Fi address
                    // disappears. Retire only that LAN generation so new bulk
                    // work cannot stall on the old router connection. Bluetooth
                    // and other interfaces keep their own sessions intact.
                    if local_address_removed(link.local_ip, &local) {
                        link.stop.send(true).ok();
                    }
                    if changed && share_addresses && link.network_info && !*link.stop.borrow() {
                        link.tx
                            .try_send(Message::Event {
                                feature: "connectivity".into(),
                                data: info.clone(),
                            })
                            .ok();
                    }
                }
            }
        }
    }
    pub(crate) async fn serve_lan_listener(self: Arc<Self>, listener: TcpListener) -> Result<()> {
        loop {
            let (socket, addr) = listener.accept().await?;
            if !local_peer(addr.ip(), &addresses()) {
                continue;
            }
            let Ok(permit) = self.limit.clone().try_acquire_owned() else {
                continue;
            };
            let engine = self.clone();
            tokio::spawn(async move {
                let _permit = permit;
                let result = async {
                    socket.set_nodelay(true)?;
                    let local_ip = socket.local_addr()?.ip();
                    engine
                        .accept_stream(
                            Box::new(socket),
                            unity_transport::Kind::Lan,
                            None,
                            Some(local_ip),
                        )
                        .await
                }
                .await;
                if let Err(error) = result {
                    tracing::debug!(peer=%addr,error=%error,"peer session ended");
                }
            });
        }
    }
    pub(crate) async fn start_extra_lan(self: &Arc<Self>, bind: &str) -> Result<()> {
        let mut ports = vec![self.port];
        let mut listeners = Vec::new();
        if let Some(listener) = compatible_listener(bind, self.port).await {
            let port = listener
                .local_addr()
                .context("companion listener address")?
                .port();
            ports.push(port);
            listeners.push(listener);
        }
        if bind == "0.0.0.0" {
            for port in &ports {
                match ipv6_listener(*port).await {
                    Ok(listener) => listeners.push(listener),
                    Err(error) => tracing::debug!(%error,"IPv6 listener unavailable"),
                }
            }
        }
        *self.lan_ports.lock().await = ports;
        for listener in listeners {
            let engine = self.clone();
            tokio::spawn(async move {
                if let Err(error) = engine.serve_lan_listener(listener).await {
                    tracing::warn!(%error,"Additional LAN listener stopped");
                }
            });
        }
        let engine = self.clone();
        let stop = self.call_alerts_stop.subscribe();
        tokio::spawn(async move {
            engine.run_network_updates(stop).await;
        });
        Ok(())
    }
}

fn advertised_hosts(mut local: Vec<Address>) -> Vec<IpAddr> {
    // Prefer active physical Wi-Fi/Ethernet addresses before VM/container/VPN
    // interfaces consume the protocol's bounded endpoint list. Keep virtual
    // networks as fallback rather than dropping them entirely.
    local.sort_by_key(|address| (address.priority, address.ip.is_ipv6(), address.ip));
    let mut seen = BTreeSet::new();
    local
        .into_iter()
        .map(|address| address.ip)
        .filter(|ip| {
            !ip.is_loopback()
                && !ip.is_unspecified()
                && !ip.is_multicast()
                && !matches!(ip, IpAddr::V6(v) if v.is_unicast_link_local())
                && seen.insert(*ip)
        })
        .take(8)
        .collect()
}

fn local_address_removed(ip: Option<IpAddr>, local: &[Address]) -> bool {
    // An empty lookup can indicate a denied/failed getifaddrs call. Avoid
    // tearing down healthy connections without a usable interface snapshot.
    !local.is_empty()
        && ip.is_some_and(|ip| {
            !local
                .iter()
                .any(|a| a.ip.to_canonical() == ip.to_canonical())
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hotspot_handover_retires_the_old_lan_but_keeps_bluetooth_and_current_addresses() {
        let current = [Address {
            ip: "10.197.29.13".parse().unwrap(),
            mask: "255.255.255.0".parse().unwrap(),
            priority: 0,
        }];
        assert!(local_address_removed(
            Some("192.168.1.38".parse().unwrap()),
            &current
        ));
        assert!(!local_address_removed(
            Some("10.197.29.13".parse().unwrap()),
            &current
        ));
        assert!(!local_address_removed(
            Some("::ffff:10.197.29.13".parse().unwrap()),
            &current
        ));
        assert!(!local_address_removed(None, &current));
        assert!(!local_address_removed(
            Some("192.168.1.38".parse().unwrap()),
            &[]
        ));
    }
    #[test]
    fn campus_shared_and_directly_connected_public_addresses_still_require_tls() {
        let local = [
            Address {
                ip: "203.0.113.20".parse().unwrap(),
                mask: "255.255.255.0".parse().unwrap(),
                priority: 0,
            },
            Address {
                ip: "2001:db8:42::1".parse().unwrap(),
                mask: "ffff:ffff:ffff:ffff::".parse().unwrap(),
                priority: 0,
            },
        ];
        for ip in [
            "100.64.0.1",
            "100.127.255.254",
            "203.0.113.99",
            "2001:db8:42::2",
            "::ffff:192.168.1.4",
            "127.0.0.1",
        ] {
            assert!(local_peer(ip.parse().unwrap(), &local), "{ip}");
        }
        for ip in [
            "100.128.0.1",
            "100.63.255.255",
            "203.0.114.1",
            "8.8.8.8",
            "2001:db8:43::2",
            "224.0.0.1",
        ] {
            assert!(!local_peer(ip.parse().unwrap(), &local), "{ip}");
        }
    }
    #[test]
    fn hotspot_address_is_kept_when_virtual_interfaces_fill_the_endpoint_budget() {
        let mut local: Vec<_> = (1..=16)
            .map(|n| Address {
                ip: Ipv4Addr::new(10, 10, n, 1).into(),
                mask: Ipv4Addr::new(255, 255, 255, 0).into(),
                priority: 1,
            })
            .collect();
        let hotspot = "192.168.182.42".parse().unwrap();
        local.push(Address {
            ip: hotspot,
            mask: "255.255.255.0".parse().unwrap(),
            priority: 0,
        });
        let hosts = advertised_hosts(local);
        assert_eq!(hosts.len(), 8);
        assert_eq!(hosts[0], hotspot);
        assert!(hosts
            .iter()
            .any(|ip| matches!(ip, IpAddr::V4(v) if v.octets()[0] == 10)));
    }
    #[tokio::test]
    async fn companion_port_skips_existing_listeners_and_respects_explicit_bind() {
        assert!(compatible_listener("127.0.0.1", unity_protocol::PORT)
            .await
            .is_none());
        assert!(compatible_listener("0.0.0.0", 17700).await.is_none());
        let first = compatible_listener("0.0.0.0", unity_protocol::PORT)
            .await
            .unwrap();
        let second = compatible_listener("0.0.0.0", unity_protocol::PORT)
            .await
            .unwrap();
        let a = first.local_addr().unwrap().port();
        let b = second.local_addr().unwrap().port();
        assert!((1714..=1764).contains(&a));
        assert!((1714..=1764).contains(&b));
        assert_ne!(a, b);
    }
    #[tokio::test]
    async fn ipv6_listener_accepts_ipv6_without_occupying_ipv4_port() {
        let listener = ipv6_listener(0).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let _ipv4 = TcpListener::bind((Ipv4Addr::LOCALHOST, port))
            .await
            .unwrap();
        let _client = tokio::net::TcpStream::connect((Ipv6Addr::LOCALHOST, port))
            .await
            .unwrap();
        let (_, peer) = listener.accept().await.unwrap();
        assert!(peer.is_ipv6());
    }
}
