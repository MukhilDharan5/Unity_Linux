//! PAM's verifier is independent of the user daemon and of transport authentication.
use anyhow::{bail, ensure, Context, Result};
use ring::{
    rand::{SecureRandom, SystemRandom},
    signature,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{File, OpenOptions},
    io::Read,
    os::unix::fs::{MetadataExt, OpenOptionsExt},
    path::Path,
    time::{SystemTime, UNIX_EPOCH},
};

pub const MAX_MESSAGE: usize = 8192;
pub const CONFIG_DIR: &str = "/etc/unity/pam/users";
pub fn now() -> Result<u64> {
    Ok(SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs())
}
pub fn hex_string(s: &str, bytes: usize) -> bool {
    s.len() == bytes * 2
        && s.bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
pub fn safe_text(s: &str, max: usize) -> bool {
    s.len() <= max && s.bytes().all(|c| (32..127).contains(&c))
}
pub fn machine_id() -> Result<String> {
    let value = std::fs::read_to_string("/etc/machine-id")?;
    ensure!(
        hex_string(value.trim(), 16),
        "Invalid local machine identity"
    );
    Ok(hex::encode(Sha256::digest(value.trim().as_bytes())))
}
pub fn socket_path(uid: u32) -> String {
    format!("/run/user/{uid}/unity-pam.sock")
}
/// Sudo can populate PAM_RHOST with this machine's hostname, even for a local request.
pub fn local_pam_host(host: &str) -> bool {
    if matches!(host, "" | "localhost" | "127.0.0.1" | "::1") {
        return true;
    }
    let mut name = [0u8; 256];
    if unsafe { libc::gethostname(name.as_mut_ptr().cast(), name.len()) } != 0 {
        return false;
    }
    let end = name.iter().position(|b| *b == 0).unwrap_or(name.len());
    std::str::from_utf8(&name[..end]).is_ok_and(|name| name == host)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Enrollment {
    pub version: u32,
    pub uid: u32,
    pub username: String,
    pub device_id: String,
    pub public_key: String,
    pub computer_fingerprint: String,
    pub machine_id: String,
    pub computer: String,
    pub services: Vec<String>,
    pub timeout_seconds: u64,
}
impl Enrollment {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.version == 1 && self.uid >= 1000 && self.uid != u32::MAX,
            "Regular local account required"
        );
        ensure!(
            !self.username.is_empty() && safe_text(&self.username, 64),
            "Invalid account"
        );
        ensure!(
            hex_string(&self.device_id, 16)
                && hex_string(&self.public_key, 65)
                && self.public_key.starts_with("04"),
            "Invalid credential"
        );
        ensure!(
            hex_string(&self.computer_fingerprint, 32) && hex_string(&self.machine_id, 32),
            "Invalid computer identity"
        );
        ensure!(
            !self.computer.is_empty() && safe_text(&self.computer, 64),
            "Invalid computer name"
        );
        ensure!(
            (10..=60).contains(&self.timeout_seconds)
                && !self.services.is_empty()
                && self.services.len() <= 8,
            "Invalid authentication policy"
        );
        ensure!(
            self.services.iter().all(|s| matches!(
                s.as_str(),
                "sudo"
                    | "sudo-i"
                    | "login"
                    | "gdm-password"
                    | "gdm-fingerprint"
                    | "polkit-1"
                    | "unity-pam-test"
            )),
            "Unsupported PAM service"
        );
        Ok(())
    }
    pub fn load(uid: u32) -> Result<Self> {
        for path in ["/etc/unity", "/etc/unity/pam", CONFIG_DIR] {
            let m = std::fs::symlink_metadata(path)?;
            ensure!(
                m.is_dir() && m.uid() == 0 && m.mode() & 0o022 == 0,
                "Untrusted PAM configuration directory"
            );
        }
        let mut file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(format!("{CONFIG_DIR}/{uid}.json"))?;
        trusted_file(&file, 0)?;
        let mut bytes = Vec::new();
        file.by_ref()
            .take((MAX_MESSAGE + 1) as u64)
            .read_to_end(&mut bytes)?;
        ensure!(bytes.len() <= MAX_MESSAGE, "Oversized PAM configuration");
        let value: Self = serde_json::from_slice(&bytes)?;
        value.validate()?;
        ensure!(
            value.uid == uid && value.machine_id == machine_id()?,
            "Enrollment does not belong to this account/computer"
        );
        Ok(value)
    }
}
pub fn trusted_file(file: &File, owner: u32) -> Result<()> {
    let m = file.metadata()?;
    ensure!(
        m.is_file() && m.uid() == owner && m.mode() & 0o077 == 0 && m.nlink() == 1,
        "Untrusted PAM enrollment file"
    );
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Challenge {
    pub version: u32,
    pub machine_id: String,
    pub computer_fingerprint: String,
    pub computer: String,
    pub uid: u32,
    pub username: String,
    pub service: String,
    pub tty: String,
    pub remote_user: String,
    pub device_id: String,
    pub nonce: String,
    pub expires: u64,
}
impl Challenge {
    pub fn new(e: &Enrollment, service: String, tty: String, remote_user: String) -> Result<Self> {
        e.validate()?;
        ensure!(e.services.contains(&service), "PAM service is not enrolled");
        let mut nonce = [0; 32];
        SystemRandom::new()
            .fill(&mut nonce)
            .map_err(|_| anyhow::anyhow!("Randomness unavailable"))?;
        let value = Self {
            version: 1,
            machine_id: e.machine_id.clone(),
            computer_fingerprint: e.computer_fingerprint.clone(),
            computer: e.computer.clone(),
            uid: e.uid,
            username: e.username.clone(),
            service,
            tty,
            remote_user,
            device_id: e.device_id.clone(),
            nonce: hex::encode(nonce),
            expires: now()? + e.timeout_seconds,
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.version == 1 && self.uid >= 1000 && self.uid != u32::MAX,
            "Invalid authentication challenge"
        );
        for text in [&self.machine_id, &self.computer_fingerprint, &self.nonce] {
            ensure!(hex_string(text, 32), "Invalid challenge identity");
        }
        ensure!(hex_string(&self.device_id, 16), "Invalid phone identity");
        ensure!(
            !self.username.is_empty() && !self.service.is_empty() && !self.computer.is_empty(),
            "Missing authentication context"
        );
        for (text, max) in [
            (&self.computer, 64),
            (&self.username, 64),
            (&self.service, 64),
            (&self.tty, 128),
            (&self.remote_user, 64),
        ] {
            ensure!(safe_text(text, max), "Unsafe authentication context");
        }
        Ok(())
    }
    /// Fixed ASCII order and final newline, shared with Android. No JSON reserialization ambiguity.
    pub fn bytes(&self) -> Result<Vec<u8>> {
        self.validate()?;
        Ok(format!(
            "unity-pam-v1\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n",
            self.machine_id,
            self.computer_fingerprint,
            self.computer,
            self.uid,
            self.username,
            self.service,
            self.tty,
            self.remote_user,
            self.device_id,
            self.nonce,
            self.expires,
            1
        )
        .into_bytes())
    }
    pub fn verify(&self, public_key: &str, signed: &str, timestamp: u64) -> Result<()> {
        ensure!(
            timestamp <= self.expires && self.expires <= timestamp.saturating_add(60),
            "Authentication challenge expired"
        );
        ensure!(
            hex_string(public_key, 65) && public_key.starts_with("04") && signed.len() <= 160,
            "Invalid biometric proof"
        );
        let signature = hex::decode(signed).context("Invalid biometric signature")?;
        signature::UnparsedPublicKey::new(
            &signature::ECDSA_P256_SHA256_ASN1,
            hex::decode(public_key)?,
        )
        .verify(&self.bytes()?, &signature)
        .map_err(|_| anyhow::anyhow!("Biometric proof rejected"))
    }
}

pub fn read_frame(reader: &mut impl Read) -> Result<Vec<u8>> {
    let mut length = [0; 4];
    reader.read_exact(&mut length)?;
    let size = u32::from_be_bytes(length) as usize;
    if size == 0 || size > MAX_MESSAGE {
        bail!("Invalid authentication frame")
    }
    let mut data = vec![0; size];
    reader.read_exact(&mut data)?;
    Ok(data)
}
/// A hostile/failed user relay cannot keep PAM waiting by trickling partial frames.
pub fn read_frame_deadline(
    stream: &mut std::os::unix::net::UnixStream,
    deadline: std::time::Instant,
) -> Result<Vec<u8>> {
    fn exact(
        stream: &mut std::os::unix::net::UnixStream,
        mut bytes: &mut [u8],
        deadline: std::time::Instant,
    ) -> Result<()> {
        while !bytes.is_empty() {
            let remaining = deadline
                .checked_duration_since(std::time::Instant::now())
                .context("Authentication timed out")?;
            stream.set_read_timeout(Some(remaining))?;
            let count = stream.read(bytes)?;
            ensure!(count > 0, "Authentication relay closed");
            bytes = &mut bytes[count..];
        }
        Ok(())
    }
    let mut length = [0; 4];
    exact(stream, &mut length, deadline)?;
    let size = u32::from_be_bytes(length) as usize;
    ensure!(
        size > 0 && size <= MAX_MESSAGE,
        "Invalid authentication frame"
    );
    let mut data = vec![0; size];
    exact(stream, &mut data, deadline)?;
    Ok(data)
}
pub fn connect_broker(path: &str) -> Result<std::os::unix::net::UnixStream> {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    ensure!(
        path.len() < address.sun_path.len() && !path.contains('\0'),
        "Invalid broker path"
    );
    address.sun_family = libc::AF_UNIX as _;
    for (dst, src) in address.sun_path.iter_mut().zip(path.bytes()) {
        *dst = src as libc::c_char;
    }
    let raw = unsafe {
        libc::socket(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
            0,
        )
    };
    ensure!(raw >= 0, "Cannot open authentication socket");
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };
    let connected = unsafe {
        libc::connect(
            fd.as_raw_fd(),
            (&address as *const libc::sockaddr_un).cast(),
            std::mem::size_of_val(&address) as _,
        )
    };
    if connected != 0 {
        // AF_UNIX uses EAGAIN for a full backlog. Fail immediately rather than blocking login.
        ensure!(
            std::io::Error::last_os_error().raw_os_error() == Some(libc::EINPROGRESS),
            "Authentication broker unavailable/busy"
        );
        let mut poll = libc::pollfd {
            fd: fd.as_raw_fd(),
            events: libc::POLLOUT,
            revents: 0,
        };
        ensure!(
            unsafe { libc::poll(&mut poll, 1, 1000) } > 0,
            "Authentication connection timed out"
        );
        let mut error: libc::c_int = 0;
        let mut length = std::mem::size_of_val(&error) as libc::socklen_t;
        ensure!(
            unsafe {
                libc::getsockopt(
                    fd.as_raw_fd(),
                    libc::SOL_SOCKET,
                    libc::SO_ERROR,
                    (&mut error as *mut libc::c_int).cast(),
                    &mut length,
                )
            } == 0
                && error == 0,
            "Authentication connection failed"
        );
    }
    let stream = std::os::unix::net::UnixStream::from(fd);
    stream.set_nonblocking(false)?;
    Ok(stream)
}
pub fn account(username: &str) -> Result<u32> {
    ensure!(!username.contains('\0'), "Invalid username");
    let name = std::ffi::CString::new(username)?;
    let mut pwd: libc::passwd = unsafe { std::mem::zeroed() };
    let mut result = std::ptr::null_mut();
    let mut buffer = vec![0u8; 65536];
    let status = unsafe {
        libc::getpwnam_r(
            name.as_ptr(),
            &mut pwd,
            buffer.as_mut_ptr().cast(),
            buffer.len(),
            &mut result,
        )
    };
    ensure!(
        status == 0 && !result.is_null() && pwd.pw_uid >= 1000 && pwd.pw_uid != u32::MAX,
        "Unknown/non-regular account"
    );
    Ok(pwd.pw_uid)
}
pub fn regular_socket(path: &Path, owner: u32) -> Result<()> {
    use std::os::unix::fs::FileTypeExt;
    let m = std::fs::symlink_metadata(path)?;
    ensure!(
        m.file_type().is_socket() && m.uid() == owner && m.mode() & 0o077 == 0,
        "Untrusted authentication socket"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::KeyPair;
    #[test]
    fn android_and_pam_verify_the_same_ecdsa_transcript() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../../Unity_Android/app/src/test/resources/pam-challenge.json"
        ))
        .unwrap();
        let c: Challenge = serde_json::from_value(fixture["challenge"].clone()).unwrap();
        assert_eq!(
            hex::encode(Sha256::digest(c.bytes().unwrap())),
            fixture["transcript_sha256"].as_str().unwrap()
        );
        c.verify(
            fixture["public_key"].as_str().unwrap(),
            fixture["signature"].as_str().unwrap(),
            1800000000,
        )
        .unwrap();
    }
    #[test]
    fn partial_authentication_frames_do_not_extend_the_absolute_deadline() {
        use std::{
            io::Write,
            time::{Duration, Instant},
        };
        let (mut reader, mut writer) = std::os::unix::net::UnixStream::pair().unwrap();
        let thread = std::thread::spawn(move || {
            for byte in [0u8, 0, 0, 4, 1, 2, 3, 4] {
                if writer.write_all(&[byte]).is_err() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(30));
            }
        });
        let started = Instant::now();
        assert!(read_frame_deadline(&mut reader, started + Duration::from_millis(70)).is_err());
        assert!(started.elapsed() < Duration::from_millis(200));
        drop(reader);
        thread.join().unwrap();
    }
    #[test]
    fn enrollment_files_must_be_private_regular_owned_files() {
        use std::os::unix::fs::PermissionsExt;
        let path =
            std::env::temp_dir().join(format!("unity-auth-permissions-{}", std::process::id()));
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
            .unwrap();
        trusted_file(&file, unsafe { libc::geteuid() }).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(trusted_file(&file, unsafe { libc::geteuid() }).is_err());
        std::fs::remove_file(path).unwrap();
    }
    fn fixture() -> (Challenge, signature::EcdsaKeyPair) {
        let rng = SystemRandom::new();
        let pkcs8 = signature::EcdsaKeyPair::generate_pkcs8(
            &signature::ECDSA_P256_SHA256_ASN1_SIGNING,
            &rng,
        )
        .unwrap();
        let key = signature::EcdsaKeyPair::from_pkcs8(
            &signature::ECDSA_P256_SHA256_ASN1_SIGNING,
            pkcs8.as_ref(),
            &rng,
        )
        .unwrap();
        let e = Enrollment {
            version: 1,
            uid: 1000,
            username: "alice".into(),
            device_id: "ab".repeat(16),
            public_key: hex::encode(key.public_key().as_ref()),
            computer_fingerprint: "cd".repeat(32),
            machine_id: "ef".repeat(32),
            computer: "Laptop".into(),
            services: vec!["sudo".into()],
            timeout_seconds: 45,
        };
        (
            Challenge::new(&e, "sudo".into(), "/dev/pts/0".into(), "alice".into()).unwrap(),
            key,
        )
    }
    #[test]
    fn proof_cannot_be_replayed_or_moved_to_other_authentication_contexts() {
        let (c, k) = fixture();
        let public = hex::encode(k.public_key().as_ref());
        let signed = hex::encode(
            k.sign(&SystemRandom::new(), &c.bytes().unwrap())
                .unwrap()
                .as_ref(),
        );
        c.verify(&public, &signed, now().unwrap()).unwrap();
        for field in [
            "nonce",
            "username",
            "service",
            "machine_id",
            "computer_fingerprint",
            "device_id",
            "tty",
            "remote_user",
            "computer",
            "uid",
            "expires",
        ] {
            let mut data = serde_json::to_value(&c).unwrap();
            if field == "uid" || field == "expires" {
                data[field] = serde_json::json!(data[field].as_u64().unwrap() + 1);
            } else {
                data[field] = serde_json::json!(if ["nonce", "machine_id", "computer_fingerprint"]
                    .contains(&field)
                {
                    "00".repeat(32)
                } else if field == "device_id" {
                    "00".repeat(16)
                } else {
                    "changed".into()
                });
            }
            let other: Challenge = serde_json::from_value(data).unwrap();
            assert!(
                other.verify(&public, &signed, now().unwrap()).is_err(),
                "{field}"
            );
        }
        assert!(c.verify(&public, &signed, c.expires + 1).is_err());
        let (_, wrong) = fixture();
        assert!(c
            .verify(
                &hex::encode(wrong.public_key().as_ref()),
                &signed,
                now().unwrap()
            )
            .is_err());
        assert!(c.verify(&public, "approved", now().unwrap()).is_err());
    }
    #[test]
    fn malformed_context_and_frames_fail_closed() {
        let (mut c, _) = fixture();
        c.service = "sudo\nlogin".into();
        assert!(c.bytes().is_err());
        for n in [0u32, 8193, u32::MAX] {
            assert!(read_frame(&mut n.to_be_bytes().as_slice()).is_err());
        }
        assert!(account("root").is_err());
    }
}
