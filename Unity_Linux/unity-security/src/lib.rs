//! Persistent TLS identity and approval-issued, per-device authentication credentials.
use anyhow::{bail, Context, Result};
use hmac::{Hmac, Mac};
use rand::RngCore;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, OpenOptions},
    io::Write,
    os::unix::{
        fs::MetadataExt,
        fs::{OpenOptionsExt, PermissionsExt},
    },
    path::{Path, PathBuf},
    sync::Arc,
};
#[derive(Clone, Serialize, Deserialize)]
pub struct Trust {
    pub name: String,
    pub secret: String,
}
#[derive(Clone, Serialize, Deserialize, Default)]
pub struct TrustStore {
    pub devices: BTreeMap<String, Trust>,
}
pub struct Identity {
    pub cert: Vec<u8>,
    pub key: Vec<u8>,
    pub fingerprint: String,
}
pub fn private_dir(path: &Path) -> Result<()> {
    fs::create_dir_all(path)?;
    let md = fs::symlink_metadata(path)?;
    if !md.is_dir() || md.uid() != unsafe { libc::geteuid() } {
        bail!("state directory must be an owned directory")
    }
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    if fs::metadata(path)?.permissions().mode() & 0o077 != 0 {
        bail!("state filesystem cannot enforce private permissions")
    }
    Ok(())
}
pub fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension(format!("tmp-{}", hex::encode(random_bytes())));
    let mut f = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&temporary)?;
    f.write_all(bytes)?;
    f.sync_all()?;
    fs::rename(&temporary, path)?;
    if let Some(parent) = path.parent() {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}
fn read_private(path: &Path) -> Result<Vec<u8>> {
    let m = fs::symlink_metadata(path)?;
    if !m.is_file() || m.uid() != unsafe { libc::geteuid() } || m.permissions().mode() & 0o077 != 0
    {
        bail!("credential file is not private")
    }
    Ok(fs::read(path)?)
}
#[derive(Serialize, Deserialize)]
struct StoredIdentity {
    cert: Vec<u8>,
    key: Vec<u8>,
}
impl Identity {
    pub fn load_or_create(dir: &Path) -> Result<Self> {
        private_dir(dir)?;
        let p = dir.join("identity.json");
        let stored: StoredIdentity = if p.exists() {
            serde_json::from_slice(&read_private(&p)?)?
        } else {
            let rcgen::CertifiedKey { cert, key_pair } =
                rcgen::generate_simple_self_signed(vec!["unity.local".into()])?;
            let value = StoredIdentity {
                cert: cert.der().to_vec(),
                key: key_pair.serialize_der(),
            };
            write_private(&p, &serde_json::to_vec(&value)?)?;
            value
        };
        let fingerprint = hex::encode(Sha256::digest(&stored.cert));
        Ok(Self {
            cert: stored.cert,
            key: stored.key,
            fingerprint,
        })
    }
    pub fn server_config(&self) -> Result<Arc<rustls::ServerConfig>> {
        let mut c =
            rustls::ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
                .with_no_client_auth()
                .with_single_cert(
                    vec![CertificateDer::from(self.cert.clone())],
                    PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key.clone())),
                )?;
        c.max_early_data_size = 0;
        Ok(Arc::new(c))
    }
}
impl TrustStore {
    pub fn load(dir: &Path) -> Result<Self> {
        let p = dir.join("trust.json");
        if p.exists() {
            let s: Self = serde_json::from_slice(&read_private(&p)?)?;
            for t in s.devices.values() {
                if hex::decode(&t.secret)?.len() != 32 {
                    bail!("invalid credential")
                }
            }
            Ok(s)
        } else {
            Ok(Self::default())
        }
    }
    pub fn save(&self, dir: &Path) -> Result<()> {
        write_private(&dir.join("trust.json"), &serde_json::to_vec(self)?)
    }
}
pub fn random_bytes() -> [u8; 32] {
    let mut b = [0; 32];
    rand::rngs::OsRng.fill_bytes(&mut b);
    b
}
pub fn proof(secret: &str, id: &str, nonce: &str) -> Result<String> {
    let key = hex::decode(secret)?;
    let mut mac = Hmac::<Sha256>::new_from_slice(&key)?;
    mac.update(format!("unity-v1:{id}:{nonce}").as_bytes());
    Ok(hex::encode(mac.finalize().into_bytes()))
}
pub fn verify(secret: &str, id: &str, nonce: &str, received: &str) -> Result<()> {
    let mut mac = Hmac::<Sha256>::new_from_slice(&hex::decode(secret)?)?;
    mac.update(format!("unity-v1:{id}:{nonce}").as_bytes());
    mac.verify_slice(&hex::decode(received).context("invalid proof")?)
        .context("authentication failed")
}
pub fn default_state_dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".local/state")
        })
        .join("unity")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn proofs_are_bound_to_device_and_session() {
        let s = hex::encode(random_bytes());
        let p = proof(&s, "a", "nonce").unwrap();
        assert!(verify(&s, "a", "nonce", &p).is_ok());
        assert!(verify(&s, "b", "nonce", &p).is_err());
        assert!(verify(&s, "a", "replay", &p).is_err());
    }
    #[test]
    fn interoperable_hmac() {
        assert_eq!(
            proof(
                &"01".repeat(32),
                "0123456789abcdef0123456789abcdef",
                &"02".repeat(32)
            )
            .unwrap(),
            "132b32a231e9d461c7946c07250e35cc38aaf226f6b9a29b2c58268b8b727272"
        );
    }
}
