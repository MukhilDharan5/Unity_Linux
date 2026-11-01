//! A root-only local relay. PAM, not this user daemon, verifies biometric signatures.
use super::Engine;
use anyhow::{ensure, Context, Result};
use serde_json::json;
use std::{
    collections::HashMap,
    os::unix::fs::{FileTypeExt, MetadataExt, PermissionsExt},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{UnixListener, UnixStream},
    sync::{oneshot, Mutex, Semaphore},
    time::timeout,
};
use unity_auth::Challenge;
use unity_protocol::Message;
pub(super) struct Pending {
    device: String,
    generation: String,
    reply: oneshot::Sender<String>,
}
#[derive(Default)]
pub(super) struct Broker {
    pending: Mutex<HashMap<String, Pending>>,
}
impl Engine {
    pub async fn start_authentication(self: &Arc<Self>) {
        let engine = self.clone();
        let result: Result<UnixListener> = (|| {
            let uid = unsafe { libc::geteuid() };
            let path = unity_auth::socket_path(uid);
            let parent = std::path::Path::new(&path)
                .parent()
                .context("No user runtime directory")?;
            let m = std::fs::symlink_metadata(parent)?;
            ensure!(
                m.is_dir() && m.uid() == uid && m.mode() & 0o077 == 0,
                "Unsafe runtime directory"
            );
            if let Ok(m) = std::fs::symlink_metadata(&path) {
                ensure!(
                    m.file_type().is_socket() && m.uid() == uid,
                    "Unexpected authentication socket"
                );
                ensure!(
                    std::os::unix::net::UnixStream::connect(&path).is_err(),
                    "Authentication broker already running"
                );
                std::fs::remove_file(&path)?;
            }
            let listener = UnixListener::bind(&path)?;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
            Ok(listener)
        })();
        let listener = match result {
            Ok(l) => l,
            Err(error) => {
                tracing::warn!(%error,"Phone authentication broker unavailable");
                return;
            }
        };
        tokio::spawn(async move {
            let slots = Arc::new(Semaphore::new(1));
            loop {
                let Ok((stream, _)) = listener.accept().await else {
                    break;
                };
                if stream.peer_cred().map(|c| c.uid() != 0).unwrap_or(true) {
                    continue;
                }
                let Ok(permit) = slots.clone().try_acquire_owned() else {
                    continue;
                };
                let engine = engine.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    if let Err(error) = engine.relay_authentication(stream).await {
                        tracing::debug!(%error,"Phone authentication not completed");
                    }
                });
            }
        });
    }
    async fn relay_authentication(&self, mut stream: UnixStream) -> Result<()> {
        let challenge = timeout(Duration::from_secs(2), async {
            let size = stream.read_u32().await? as usize;
            ensure!(
                size > 0 && size <= unity_auth::MAX_MESSAGE,
                "Invalid PAM request"
            );
            let mut bytes = vec![0; size];
            stream.read_exact(&mut bytes).await?;
            Ok::<_, anyhow::Error>(serde_json::from_slice::<Challenge>(&bytes)?)
        })
        .await??;
        challenge.validate()?;
        ensure!(
            challenge.uid == unsafe { libc::geteuid() }
                && challenge.machine_id == unity_auth::machine_id()?
                && challenge.computer_fingerprint == self.identity.fingerprint,
            "PAM request is for another computer/account"
        );
        let now = unity_auth::now()?;
        ensure!(
            challenge.expires > now && challenge.expires <= now + 60,
            "Expired PAM request"
        );
        let (tx, rx) = oneshot::channel();
        let request = uuid::Uuid::new_v4().simple().to_string();
        {
            let state = self.state.lock().await;
            let d = state
                .devices
                .get(&challenge.device_id)
                .context("Enrolled phone is not paired")?;
            ensure!(
                !d.disabled.iter().any(|f| f == "authentication"),
                "Phone authentication disabled"
            );
            let links = state
                .sessions
                .get(&challenge.device_id)
                .context("Enrolled phone is disconnected")?;
            let link = links
                .0
                .iter()
                .filter(|l| l.biometric_auth && !*l.stop.borrow())
                .min_by_key(|l| l.kind != unity_transport::Kind::Bluetooth)
                .context("Enable fingerprint approval on the enrolled phone")?;
            self.authentication.pending.lock().await.insert(
                request.clone(),
                Pending {
                    device: challenge.device_id.clone(),
                    generation: link.generation.clone(),
                    reply: tx,
                },
            );
            if link
                .tx
                .try_send(Message::AuthRequest {
                    id: request.clone(),
                    challenge: serde_json::to_value(&challenge)?,
                })
                .is_err()
            {
                self.authentication.pending.lock().await.remove(&request);
                anyhow::bail!("Phone authentication queue full");
            }
        }
        // If PAM exits/cancels, withdraw the phone request rather than accepting a late fingerprint.
        let remaining = Duration::from_secs(challenge.expires - now);
        let result = tokio::select! {
            result=timeout(remaining,rx)=>result.ok().and_then(Result::ok),
            _=stream.read_u8()=>None,
        };
        let pending = self.authentication.pending.lock().await.remove(&request);
        if let Some(p) = pending {
            let state = self.state.lock().await;
            if let Some(link) = state
                .sessions
                .get(&p.device)
                .and_then(|links| links.generation(&p.generation))
            {
                link.tx
                    .try_send(Message::AuthRequest {
                        id: request.clone(),
                        challenge: serde_json::Value::Null,
                    })
                    .ok();
            }
        }
        let reply = serde_json::to_vec(&json!({"signature":result.unwrap_or_default()}))?;
        timeout(Duration::from_secs(2), async {
            stream.write_u32(reply.len() as u32).await?;
            stream.write_all(&reply).await
        })
        .await??;
        Ok(())
    }
    pub(super) async fn authentication_response(
        &self,
        device: &str,
        generation: &str,
        id: &str,
        signature: String,
    ) {
        if !unity_protocol::valid_id(id) || signature.len() > 160 {
            return;
        }
        let mut pending = self.authentication.pending.lock().await;
        if pending
            .get(id)
            .is_some_and(|p| p.device == device && p.generation == generation)
        {
            if let Some(p) = pending.remove(id) {
                p.reply.send(signature).ok();
            }
        }
    }
    pub(super) async fn authentication_disconnected(&self, generation: &str) {
        self.authentication
            .pending
            .lock()
            .await
            .retain(|_, p| p.generation != generation);
    }
}

#[cfg(test)]
mod tests {
    use super::super::{Device, Session, Sessions};
    use super::*;
    use std::collections::BTreeMap;
    use tokio::sync::{mpsc, watch};
    async fn fixture() -> (Arc<Engine>, mpsc::Receiver<Message>, Challenge) {
        let dir = std::env::temp_dir().join(format!("unity-auth-broker-{}", uuid::Uuid::new_v4()));
        let engine = Engine::new(dir, 0).unwrap();
        let device = "ab".repeat(16);
        let (tx, rx) = mpsc::channel(8);
        let (stop, _) = watch::channel(false);
        {
            let mut state = engine.state.lock().await;
            state.devices.insert(
                device.clone(),
                Device {
                    id: device.clone(),
                    name: "Phone".into(),
                    connected: true,
                    capabilities: Vec::new(),
                    state: BTreeMap::new(),
                    phone_artwork: BTreeMap::new(),
                    disabled: Vec::new(),
                    last_error: String::new(),
                },
            );
            state.sessions.insert(
                device.clone(),
                Sessions(vec![Session {
                    local_ip: None,
                    kind: unity_transport::Kind::Bluetooth,
                    network_info: false,
                    biometric_auth: true,
                    generation: "generation-a".into(),
                    started_at: 0,
                    tx,
                    stop,
                }]),
            );
        }
        let challenge = Challenge {
            version: 1,
            machine_id: unity_auth::machine_id().unwrap(),
            computer_fingerprint: engine.identity.fingerprint.clone(),
            computer: "Laptop".into(),
            uid: unsafe { libc::geteuid() },
            username: "fixture-user".into(),
            service: "sudo".into(),
            tty: "/dev/pts/0".into(),
            remote_user: "fixture-user".into(),
            device_id: device,
            nonce: "cd".repeat(32),
            expires: unity_auth::now().unwrap() + 30,
        };
        (engine, rx, challenge)
    }
    async fn write_request(stream: &mut UnixStream, challenge: &Challenge) {
        let bytes = serde_json::to_vec(challenge).unwrap();
        stream.write_u32(bytes.len() as u32).await.unwrap();
        stream.write_all(&bytes).await.unwrap();
    }
    #[tokio::test]
    async fn biometric_proof_returns_only_to_its_original_root_request_and_session() {
        let (engine, mut phone, challenge) = fixture().await;
        let (mut client, server) = UnixStream::pair().unwrap();
        let task = tokio::spawn({
            let engine = engine.clone();
            async move { engine.relay_authentication(server).await }
        });
        write_request(&mut client, &challenge).await;
        let Message::AuthRequest {
            id,
            challenge: data,
        } = phone.recv().await.unwrap()
        else {
            panic!("Expected authentication request")
        };
        assert_eq!(data["nonce"], challenge.nonce);
        engine
            .authentication_response(
                &challenge.device_id,
                "wrong-generation",
                &id,
                "wrong".into(),
            )
            .await;
        engine
            .authentication_response(&"ef".repeat(16), "generation-a", &id, "wrong".into())
            .await;
        assert_eq!(engine.authentication.pending.lock().await.len(), 1);
        engine
            .authentication_response(&challenge.device_id, "generation-a", &id, "0123abcd".into())
            .await;
        let length = client.read_u32().await.unwrap();
        let mut bytes = vec![0; length as usize];
        client.read_exact(&mut bytes).await.unwrap();
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&bytes).unwrap()["signature"],
            "0123abcd"
        );
        task.await.unwrap().unwrap();
        assert!(engine.authentication.pending.lock().await.is_empty());
        engine
            .authentication_response(&challenge.device_id, "generation-a", &id, "replay".into())
            .await;
        assert!(engine.authentication.pending.lock().await.is_empty());
        std::fs::remove_dir_all(&engine.dir).unwrap();
    }
    #[tokio::test]
    async fn cancelling_pam_withdraws_phone_prompt_and_late_proofs_are_discarded() {
        let (engine, mut phone, challenge) = fixture().await;
        let (mut client, server) = UnixStream::pair().unwrap();
        let task = tokio::spawn({
            let engine = engine.clone();
            async move { engine.relay_authentication(server).await }
        });
        write_request(&mut client, &challenge).await;
        let Message::AuthRequest { id, .. } = phone.recv().await.unwrap() else {
            panic!("Expected request")
        };
        drop(client);
        let Message::AuthRequest {
            id: cancel,
            challenge: data,
        } = phone.recv().await.unwrap()
        else {
            panic!("Expected withdrawal")
        };
        assert_eq!(id, cancel);
        assert!(data.is_null());
        let _ = task.await;
        assert!(engine.authentication.pending.lock().await.is_empty());
        engine
            .authentication_response(&challenge.device_id, "generation-a", &id, "late".into())
            .await;
        assert!(engine.authentication.pending.lock().await.is_empty());
        std::fs::remove_dir_all(&engine.dir).unwrap();
    }
    #[tokio::test]
    async fn link_loss_returns_no_signature_and_does_not_move_authentication_to_other_links() {
        let (engine, mut phone, challenge) = fixture().await;
        let (mut client, server) = UnixStream::pair().unwrap();
        let task = tokio::spawn({
            let engine = engine.clone();
            async move { engine.relay_authentication(server).await }
        });
        write_request(&mut client, &challenge).await;
        phone.recv().await.unwrap();
        engine.authentication_disconnected("generation-a").await;
        let length = client.read_u32().await.unwrap();
        let mut bytes = vec![0; length as usize];
        client.read_exact(&mut bytes).await.unwrap();
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&bytes).unwrap()["signature"],
            ""
        );
        task.await.unwrap().unwrap();
        std::fs::remove_dir_all(&engine.dir).unwrap();
    }
}
