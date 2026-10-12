//! GUI-free lifecycle, pairing, authorization-neutral command routing and state.
use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::Serialize;
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashMap},
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    net::{TcpListener, UdpSocket},
    sync::{broadcast, mpsc, oneshot, watch, Mutex, Semaphore},
    time::{interval, timeout},
};
use unity_protocol::{Availability, Capability, Message, DISCOVERY_PORT};
use unity_security::{Identity, Trust, TrustStore};
use unity_transport::{network::LanTransport, Transport};
use uuid::Uuid;
#[derive(Clone, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub connected: bool,
    pub capabilities: Vec<Capability>,
    pub state: BTreeMap<String, Value>,
    pub disabled: Vec<String>,
    pub last_error: String,
}
#[derive(Clone, Serialize)]
pub struct Operation {
    pub id: String,
    pub device: String,
    pub feature: String,
    pub action: String,
    pub status: String,
    pub progress: u64,
    pub result: Value,
    pub error: String,
    #[serde(skip)]
    pub owner: String,
    #[serde(skip)]
    pub started: Option<Instant>,
}
struct Session {
    generation: String,
    tx: mpsc::Sender<Message>,
    stop: watch::Sender<bool>,
}
struct PendingPair {
    name: String,
    approve: oneshot::Sender<bool>,
    expires: Instant,
}
#[derive(Default)]
struct State {
    trust: TrustStore,
    devices: BTreeMap<String, Device>,
    sessions: HashMap<String, Session>,
    pairs: HashMap<String, PendingPair>,
    operations: BTreeMap<String, Operation>,
    discovery_until: Option<Instant>,
}
pub struct Engine {
    state: Mutex<State>,
    pub identity: Identity,
    pub dir: PathBuf,
    pub changed: broadcast::Sender<String>,
    pub port: u16,
    limit: Arc<Semaphore>,
}
impl Engine {
    pub fn new(dir: PathBuf, port: u16) -> Result<Arc<Self>> {
        let identity = Identity::load_or_create(&dir)?;
        let trust = TrustStore::load(&dir)?;
        let mut devices = BTreeMap::new();
        let toggles: BTreeMap<String, Vec<String>> = if dir.join("features.json").exists() {
            serde_json::from_slice(&std::fs::read(dir.join("features.json"))?)?
        } else {
            BTreeMap::new()
        };
        for (id, t) in &trust.devices {
            devices.insert(
                id.clone(),
                Device {
                    id: id.clone(),
                    name: t.name.clone(),
                    connected: false,
                    capabilities: unity_plugins::capabilities(&[]),
                    state: BTreeMap::new(),
                    disabled: toggles.get(id).cloned().unwrap_or_default(),
                    last_error: String::new(),
                },
            );
        }
        let (changed, _) = broadcast::channel(128);
        Ok(Arc::new(Self {
            state: Mutex::new(State {
                trust,
                devices,
                ..Default::default()
            }),
            identity,
            dir,
            changed,
            port,
            limit: Arc::new(Semaphore::new(16)),
        }))
    }
    pub async fn devices(&self) -> Vec<Device> {
        self.state.lock().await.devices.values().cloned().collect()
    }
    pub async fn device(&self, id: &str) -> Result<Device> {
        self.state
            .lock()
            .await
            .devices
            .get(id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("unknown device"))
    }
    pub async fn pending_pairs(&self) -> Value {
        let s = self.state.lock().await;
        json!(s
            .pairs
            .iter()
            .filter(|(_, p)| p.expires > Instant::now())
            .map(|(id, p)| json!({"id":id,"name":p.name}))
            .collect::<Vec<_>>())
    }
    pub async fn discover(&self, seconds: u32) -> Result<()> {
        if seconds == 0 || seconds > 120 {
            bail!("discovery window must be 1..120 seconds")
        };
        self.state.lock().await.discovery_until =
            Some(Instant::now() + Duration::from_secs(seconds as u64));
        self.changed.send(String::new()).ok();
        Ok(())
    }
    pub async fn approve_pair(&self, id: &str, approve: bool) -> Result<()> {
        let p = self
            .state
            .lock()
            .await
            .pairs
            .remove(id)
            .ok_or_else(|| anyhow::anyhow!("pair request not found"))?;
        if p.expires < Instant::now() {
            bail!("pair request expired")
        };
        p.approve
            .send(approve)
            .map_err(|_| anyhow::anyhow!("pairing connection closed"))?;
        Ok(())
    }
    pub async fn unpair(&self, id: &str) -> Result<()> {
        let mut s = self.state.lock().await;
        let mut next = s.trust.clone();
        if next.devices.remove(id).is_none() {
            bail!("unknown trusted device")
        };
        next.save(&self.dir)?;
        s.trust = next;
        if let Some(sess) = s.sessions.remove(id) {
            sess.stop.send(true).ok();
        }
        s.devices.remove(id);
        self.changed.send(id.into()).ok();
        Ok(())
    }
    pub async fn disconnect(&self, id: &str) -> Result<()> {
        let s = self.state.lock().await;
        let session = s
            .sessions
            .get(id)
            .ok_or_else(|| anyhow::anyhow!("device disconnected"))?;
        session.stop.send(true).ok();
        Ok(())
    }
    pub async fn set_enabled(&self, id: &str, feature: &str, enabled: bool) -> Result<()> {
        if unity_plugins::plugin(feature).is_none() {
            bail!("unknown feature")
        };
        let mut s = self.state.lock().await;
        let d = s
            .devices
            .get_mut(id)
            .ok_or_else(|| anyhow::anyhow!("unknown device"))?;
        d.disabled.retain(|f| f != feature);
        if !enabled {
            d.disabled.push(feature.into());
            d.state.remove(feature);
        }
        let values: BTreeMap<_, _> = s
            .devices
            .iter()
            .map(|(id, d)| (id.clone(), d.disabled.clone()))
            .collect();
        unity_security::write_private(
            &self.dir.join("features.json"),
            &serde_json::to_vec(&values)?,
        )?;
        self.changed.send(id.into()).ok();
        Ok(())
    }
    pub async fn operations(&self, owner: &str) -> Vec<Operation> {
        self.state
            .lock()
            .await
            .operations
            .values()
            .filter(|o| o.owner == owner)
            .cloned()
            .collect()
    }
    pub async fn operation(&self, id: &str, owner: &str) -> Result<Operation> {
        let s = self.state.lock().await;
        let o = s
            .operations
            .get(id)
            .ok_or_else(|| anyhow::anyhow!("unknown operation"))?;
        if o.owner != owner {
            bail!("operation belongs to another client")
        };
        Ok(o.clone())
    }
    pub async fn cancel(&self, id: &str, owner: &str) -> Result<()> {
        let mut s = self.state.lock().await;
        let o = s
            .operations
            .get_mut(id)
            .ok_or_else(|| anyhow::anyhow!("unknown operation"))?;
        if o.owner != owner {
            bail!("operation belongs to another client")
        };
        if o.status == "running" {
            o.status = "cancelled".into();
            o.error =
                "Cancelled locally; remote actions already dispatched may have occurred".into();
        }
        self.changed.send(format!("operation:{id}")).ok();
        Ok(())
    }
    pub async fn execute(
        self: &Arc<Self>,
        device: &str,
        feature: &str,
        action: &str,
        data: Value,
        owner: &str,
    ) -> Result<String> {
        let plugin =
            unity_plugins::plugin(feature).ok_or_else(|| anyhow::anyhow!("unknown feature"))?;
        plugin.command(action, &data)?;
        let mut s = self.state.lock().await;
        let d = s
            .devices
            .get(device)
            .ok_or_else(|| anyhow::anyhow!("unknown device"))?;
        if !d.connected {
            bail!("device disconnected")
        };
        if d.disabled.iter().any(|f| f == feature) {
            bail!("feature disabled")
        };
        if !d
            .capabilities
            .iter()
            .any(|c| c.id == feature && unity_plugins::command_available(c))
        {
            bail!("feature unavailable or permission required")
        }
        let expired: Vec<_> = s
            .operations
            .iter()
            .filter(|(_, o)| {
                o.status != "running"
                    && o.started
                        .is_some_and(|t| t.elapsed() > Duration::from_secs(600))
            })
            .map(|(id, _)| id.clone())
            .collect();
        for expired_id in expired {
            s.operations.remove(&expired_id);
            self.changed
                .send(format!("remove_operation:{expired_id}"))
                .ok();
        }
        if s.operations.len() >= 512 {
            bail!("operation history limit reached; completed operations expire after ten minutes")
        }
        let tx = s
            .sessions
            .get(device)
            .context("device disconnected")?
            .tx
            .clone();
        let id = Uuid::new_v4().simple().to_string();
        s.operations.insert(
            id.clone(),
            Operation {
                id: id.clone(),
                device: device.into(),
                feature: feature.into(),
                action: action.into(),
                status: "running".into(),
                progress: 0,
                result: Value::Null,
                error: String::new(),
                owner: owner.into(),
                started: Some(Instant::now()),
            },
        );
        drop(s);
        let engine = self.clone();
        let op = id.clone();
        let f = feature.to_string();
        let a = action.to_string();
        tokio::spawn(async move {
            let send_result = if f == "files" && a == "send" {
                engine.send_file(&op, &tx, &data).await
            } else {
                timeout(
                    Duration::from_secs(10),
                    tx.send(Message::Command {
                        id: op.clone(),
                        feature: f,
                        action: a,
                        data,
                    }),
                )
                .await
                .map_err(anyhow::Error::from)
                .and_then(|v| v.map_err(anyhow::Error::from))
            };
            if let Err(e) = send_result {
                engine.finish(&op, false, Value::Null, e.to_string()).await;
                return;
            }
            tokio::time::sleep(Duration::from_secs(60)).await;
            let running = engine
                .state
                .lock()
                .await
                .operations
                .get(&op)
                .is_some_and(|o| o.status == "running");
            if running {
                engine
                    .finish(&op, false, Value::Null, "Remote operation timed out".into())
                    .await;
            }
        });
        Ok(id)
    }
    async fn send_file(&self, id: &str, tx: &mpsc::Sender<Message>, data: &Value) -> Result<()> {
        use std::{io::Seek, os::unix::fs::OpenOptionsExt};
        use tokio::io::AsyncReadExt;
        let path = PathBuf::from(data["path"].as_str().context("missing path")?);
        let prepared = tokio::task::spawn_blocking(move || -> Result<_> {
            let mut file = std::fs::OpenOptions::new()
                .read(true)
                .custom_flags(libc_flag_nofollow())
                .open(&path)?;
            let md = file.metadata()?;
            if !md.is_file() || md.len() > unity_plugins::transfer::MAX_FILE {
                bail!("source must be a regular file of at most 64 MiB")
            };
            let name = path
                .file_name()
                .and_then(|n| n.to_str())
                .context("invalid filename")?
                .to_string();
            unity_plugins::transfer::safe_name(&name)?;
            // Hash in bounded chunks; the same opened descriptor is rewound for transmission.
            let mut hash = sha256_stream(&mut file)?;
            file.rewind()?;
            Ok((file, name, md.len(), std::mem::take(&mut hash)))
        })
        .await??;
        let (file, name, total, digest) = prepared;
        let mut file = tokio::fs::File::from_std(file);
        let send = |action: &str, data: Value| {
            let message = Message::Command {
                id: id.into(),
                feature: "files".into(),
                action: action.into(),
                data,
            };
            let tx = tx.clone();
            async move {
                timeout(Duration::from_secs(10), tx.send(message)).await??;
                Ok::<_, anyhow::Error>(())
            }
        };
        send(
            "begin",
            json!({"id":id,"name":name,"size":total,"sha256":digest}),
        )
        .await?;
        let mut buffer = vec![0; 49152];
        let mut sent = 0;
        loop {
            if self
                .state
                .lock()
                .await
                .operations
                .get(id)
                .is_none_or(|o| o.status != "running")
            {
                send("abort", json!({"id":id})).await.ok();
                return Ok(());
            }
            let n = file.read(&mut buffer).await?;
            if n == 0 {
                break;
            };
            send(
                "chunk",
                json!({"id":id,"data":STANDARD.encode(&buffer[..n])}),
            )
            .await?;
            sent += n as u64;
            let mut changed = false;
            if let Some(o) = self.state.lock().await.operations.get_mut(id) {
                let progress = (sent * 100).checked_div(total).unwrap_or(100);
                changed = progress != o.progress;
                o.progress = progress;
            }
            if changed {
                self.changed.send(format!("operation:{id}")).ok();
            }
        }
        send("end", json!({"id":id})).await?;
        Ok(())
    }
    async fn finish(&self, id: &str, ok: bool, data: Value, error: String) {
        let mut s = self.state.lock().await;
        if let Some(o) = s.operations.get_mut(id) {
            if o.status != "running" {
                return;
            };
            o.status = if ok { "completed" } else { "failed" }.into();
            o.result = data;
            o.error = error;
            o.progress = if ok { 100 } else { o.progress };
        }
        self.changed.send(format!("operation:{id}")).ok();
    }
    pub async fn serve(self: Arc<Self>, bind: &str) -> Result<()> {
        let listener = TcpListener::bind((bind, self.port)).await?;
        let config = self.identity.server_config()?;
        let engine = self.clone();
        tokio::spawn(async move {
            if let Err(e) = engine.discovery_server(bind_any()).await {
                tracing::warn!(error=%e,"LAN discovery disabled")
            }
        });
        loop {
            let (socket, addr) = listener.accept().await?;
            if !match addr.ip() {
                std::net::IpAddr::V4(ip) => {
                    ip.is_private() || ip.is_loopback() || ip.is_link_local()
                }
                std::net::IpAddr::V6(ip) => {
                    ip.is_unique_local() || ip.is_loopback() || ip.is_unicast_link_local()
                }
            } {
                continue;
            }
            let Ok(permit) = self.limit.clone().try_acquire_owned() else {
                continue;
            };
            let acceptor = tokio_rustls_acceptor(config.clone());
            let engine = self.clone();
            tokio::spawn(async move {
                let _permit = permit;
                let result = async {
                    let tls = timeout(Duration::from_secs(10), acceptor.accept(socket)).await??;
                    let mut transport = LanTransport::new(tls);
                    engine.authenticate_and_run(&mut transport).await
                }
                .await;
                if let Err(e) = result {
                    tracing::debug!(peer=%addr,error=%e,"peer session ended")
                }
            });
        }
    }
    async fn discovery_server(self: Arc<Self>, bind: &str) -> Result<()> {
        let socket = UdpSocket::bind((bind, DISCOVERY_PORT)).await?;
        let mut b = [0; 64];
        let mut last = HashMap::new();
        loop {
            let (n, addr) = socket.recv_from(&mut b).await?;
            if &b[..n] != b"UNITY_DISCOVER_V1" {
                continue;
            };
            let enabled = self
                .state
                .lock()
                .await
                .discovery_until
                .is_some_and(|t| t > Instant::now());
            if !enabled {
                continue;
            };
            if last
                .get(&addr.ip())
                .is_some_and(|t: &Instant| t.elapsed() < Duration::from_secs(1))
            {
                continue;
            };
            if last.len() > 128 {
                last.clear()
            };
            last.insert(addr.ip(), Instant::now());
            let payload = serde_json::to_vec(
                &json!({"version":1,"name":"Unity Linux","port":self.port,"fingerprint":self.identity.fingerprint}),
            )?;
            socket.send_to(&payload, addr).await?;
        }
    }
    async fn authenticate_and_run(self: &Arc<Self>, t: &mut dyn Transport) -> Result<()> {
        let hello = timeout(Duration::from_secs(10), t.receive()).await??;
        let Message::Hello {
            version,
            device_id: id,
            name,
            capabilities,
        } = hello
        else {
            bail!("expected hello")
        };
        unity_protocol::validate_hello(version, &id, &name, &capabilities)?;
        let trusted = self.state.lock().await.trust.devices.get(&id).cloned();
        if let Some(trust) = trusted {
            let nonce = hex::encode(unity_security::random_bytes());
            t.send(&Message::Challenge {
                nonce: nonce.clone(),
            })
            .await?;
            let Message::Proof { mac } = timeout(Duration::from_secs(10), t.receive()).await??
            else {
                bail!("expected authentication proof")
            };
            unity_security::verify(&trust.secret, &id, &nonce, &mac)?;
        } else {
            let (rx, name_copy) = {
                let mut s = self.state.lock().await;
                if !s
                    .discovery_until
                    .is_some_and(|until| until > Instant::now())
                {
                    bail!("pairing is closed")
                };
                if s.pairs.len() >= 8 || s.pairs.contains_key(&id) {
                    bail!("pair request limit")
                };
                let (tx, rx) = oneshot::channel();
                s.pairs.insert(
                    id.clone(),
                    PendingPair {
                        name: name.clone(),
                        approve: tx,
                        expires: Instant::now() + Duration::from_secs(90),
                    },
                );
                (rx, name.clone())
            };
            self.changed.send(String::new()).ok();
            t.send(&Message::PairPending {
                device_id: id.clone(),
            })
            .await?;
            let approved = timeout(Duration::from_secs(90), rx).await;
            self.state.lock().await.pairs.remove(&id);
            if !matches!(approved, Ok(Ok(true))) {
                bail!("pairing rejected or expired")
            }
            let secret = hex::encode(unity_security::random_bytes());
            {
                let mut s = self.state.lock().await;
                let mut trust = s.trust.clone();
                trust.devices.insert(
                    id.clone(),
                    Trust {
                        name: name_copy.clone(),
                        secret: secret.clone(),
                    },
                );
                trust.save(&self.dir)?;
                s.trust = trust;
                s.devices.insert(
                    id.clone(),
                    Device {
                        id: id.clone(),
                        name: name_copy,
                        connected: false,
                        capabilities: unity_plugins::capabilities(&capabilities),
                        state: BTreeMap::new(),
                        disabled: vec![],
                        last_error: String::new(),
                    },
                );
            }
            self.changed.send(id.clone()).ok();
            t.send(&Message::PairResult { secret }).await?;
            return Ok(());
        }
        let (tx, mut rx) = mpsc::channel(16);
        let (stop, mut cancel) = watch::channel(false);
        let generation = Uuid::new_v4().to_string();
        {
            let mut s = self.state.lock().await;
            if !s.trust.devices.contains_key(&id) || !s.devices.contains_key(&id) {
                bail!("device revoked during authentication")
            }
            if let Some(old) = s.sessions.insert(
                id.clone(),
                Session {
                    generation: generation.clone(),
                    tx,
                    stop,
                },
            ) {
                old.stop.send(true).ok();
            };
            let d = s.devices.get_mut(&id).context("device was revoked")?;
            d.connected = true;
            d.name = name;
            d.state.clear();
            d.last_error.clear();
            d.capabilities = unity_plugins::capabilities(&capabilities);
        }
        self.changed.send(id.clone()).ok();
        let result=async {
 t.send(&Message::Ready).await?;let mut heartbeat=interval(Duration::from_secs(30));heartbeat.tick().await;
 let mut last_seen=Instant::now();let mut files=unity_plugins::transfer::Receiver::new(self.dir.join("received"))?;
 loop{tokio::select!{
 _=cancel.changed()=>{bail!("session cancelled")},
 _=heartbeat.tick()=>{if last_seen.elapsed()>Duration::from_secs(90){bail!("peer heartbeat timed out")};timeout(Duration::from_secs(10),t.send(&Message::Ping)).await??;},
 outbound=rx.recv()=>{let Some(m)=outbound else{break};timeout(Duration::from_secs(10),t.send(&m)).await??;},
 incoming=t.receive()=>{let message=incoming?;last_seen=Instant::now();match message{
 Message::Ping=>{t.send(&Message::Pong).await?},Message::Pong=>{},
 Message::Capabilities{capabilities}=>{unity_protocol::validate_hello(1,&id,"device",&capabilities)?;if let Some(d)=self.state.lock().await.devices.get_mut(&id){d.capabilities=unity_plugins::capabilities(&capabilities);let allowed:Vec<_>=d.capabilities.iter().filter(|c|c.status==Availability::Available).map(|c|c.id.clone()).collect();d.state.retain(|f,_|allowed.contains(f));}self.changed.send(id.clone()).ok();},
 Message::Event{feature,data}=>{
 let p=unity_plugins::plugin(&feature).context("unknown event feature")?;p.event(&data)?;
 let allowed={let s=self.state.lock().await;let d=s.devices.get(&id).context("revoked device")?;!d.disabled.contains(&feature)&&d.capabilities.iter().any(|c|c.id==feature&&c.status==Availability::Available)};
 if !allowed{continue};
 let value=if feature=="files"{let chunk=data["phase"]=="chunk";let received=files.event(&data)?;if chunk{continue};if let Some(path)=received{json!({"received_path":path,"state":"completed"})}else{json!({"state":"receiving","id":data["id"]})}}else{data};
 let mut s=self.state.lock().await;let d=s.devices.get_mut(&id).context("revoked device")?;
 if feature=="notifications"{let notifications=d.state.entry(feature.clone()).or_insert_with(||json!({})).as_object_mut().context("invalid notification state")?;let key=value["id"].as_str().context("missing notification ID")?.to_string();if value["removed"].as_bool().unwrap_or(false){notifications.remove(&key);}else{if notifications.len()>=200{notifications.clear()};notifications.insert(key,value);}}else{d.state.insert(feature,value);}
 drop(s);self.changed.send(id.clone()).ok();
 },
 Message::Result{id:op,ok,data,error}=>{if error.len()>512||serde_json::to_vec(&data)?.len()>unity_protocol::MAX_TEXT{bail!("operation response too large")};let operation=self.state.lock().await.operations.get(&op).filter(|o|o.device==id&&o.status=="running").cloned();if let Some(operation)=operation{if ok{unity_plugins::previews::validate_result(&operation.feature,&operation.action,&data)?;}self.finish(&op,ok,data,error).await}},
 _=>bail!("unexpected session message")
 }}
 }}Ok::<_,anyhow::Error>(())
 }.await;
        let mut s = self.state.lock().await;
        if s.sessions
            .get(&id)
            .is_some_and(|sess| sess.generation == generation)
        {
            s.sessions.remove(&id);
            if let Some(d) = s.devices.get_mut(&id) {
                d.connected = false;
                d.state.clear();
                d.last_error = result
                    .as_ref()
                    .err()
                    .map(|e| e.to_string())
                    .unwrap_or_default();
            }
            for o in s
                .operations
                .values_mut()
                .filter(|o| o.device == id && o.status == "running")
            {
                o.status = "failed".into();
                o.error = "Device disconnected".into();
            }
            self.changed.send(id).ok();
        }
        result
    }
}
fn bind_any() -> &'static str {
    "0.0.0.0"
}
fn libc_flag_nofollow() -> i32 {
    libc::O_NOFOLLOW
}
fn sha256_stream(file: &mut std::fs::File) -> Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut h = Sha256::new();
    let mut b = [0; 65536];
    loop {
        let n = file.read(&mut b)?;
        if n == 0 {
            break;
        };
        h.update(&b[..n])
    }
    Ok(format!("{:x}", h.finalize()))
}
fn tokio_rustls_acceptor(config: Arc<rustls::ServerConfig>) -> tokio_rustls::TlsAcceptor {
    tokio_rustls::TlsAcceptor::from(config)
}
