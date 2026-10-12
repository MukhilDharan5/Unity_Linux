//! Bounded, streamed, hash-verified transfers into private quarantine. Never uses peer paths.
use crate::text;
use anyhow::{bail, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{File, OpenOptions},
    io::Write,
    os::unix::fs::OpenOptionsExt,
    path::PathBuf,
};
pub const MAX_FILE: u64 = 64 * 1024 * 1024;
struct Incoming {
    file: File,
    path: PathBuf,
    name: String,
    total: u64,
    written: u64,
    hash: Sha256,
    digest: String,
}
pub struct Receiver {
    root: PathBuf,
    incoming: HashMap<String, Incoming>,
}
pub fn safe_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name.len() > 255
        || name == "."
        || name == ".."
        || name
            .chars()
            .any(|c| c == '/' || c == '\\' || c.is_control())
    {
        bail!("unsafe file name")
    }
    Ok(())
}
impl Receiver {
    pub fn new(root: PathBuf) -> Result<Self> {
        std::fs::create_dir_all(&root)?;
        Ok(Self {
            root,
            incoming: HashMap::new(),
        })
    }
    pub fn event(&mut self, v: &Value) -> Result<Option<PathBuf>> {
        let id = text(v, "id", 32)?;
        if !unity_protocol::valid_id(id) {
            bail!("invalid transfer id")
        }
        match text(v, "phase", 16)? {
            "begin" => {
                if self.incoming.len() >= 4 || self.incoming.contains_key(id) {
                    bail!("transfer limit reached")
                };
                let name = text(v, "name", 255)?;
                safe_name(name)?;
                let total = v["size"]
                    .as_u64()
                    .ok_or_else(|| anyhow::anyhow!("missing file size"))?;
                if total > MAX_FILE {
                    bail!("file exceeds 64 MiB limit")
                };
                let digest = text(v, "sha256", 64)?;
                if digest.len() != 64 || !digest.bytes().all(|c| c.is_ascii_hexdigit()) {
                    bail!("invalid hash")
                };
                let path = self.root.join(format!(".{id}.part"));
                let file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .custom_flags(libc::O_NOFOLLOW)
                    .open(&path)?;
                self.incoming.insert(
                    id.into(),
                    Incoming {
                        file,
                        path,
                        name: name.into(),
                        total,
                        written: 0,
                        hash: Sha256::new(),
                        digest: digest.into(),
                    },
                );
            }
            "chunk" => {
                let t = self
                    .incoming
                    .get_mut(id)
                    .ok_or_else(|| anyhow::anyhow!("unknown transfer"))?;
                let bytes = STANDARD.decode(text(v, "data", 65536)?)?;
                if bytes.len() > 49152 || t.written + bytes.len() as u64 > t.total {
                    bail!("invalid chunk size")
                };
                t.file.write_all(&bytes)?;
                t.hash.update(&bytes);
                t.written += bytes.len() as u64;
            }
            "end" => {
                let t = self
                    .incoming
                    .remove(id)
                    .ok_or_else(|| anyhow::anyhow!("unknown transfer"))?;
                let actual = format!("{:x}", t.hash.finalize());
                if t.written != t.total || actual != t.digest {
                    std::fs::remove_file(&t.path)?;
                    bail!("transfer integrity failed")
                };
                t.file.sync_all()?;
                let final_path = self.root.join(format!("{id}-{}", t.name));
                if final_path.exists() {
                    std::fs::remove_file(t.path)?;
                    bail!("destination exists")
                };
                std::fs::rename(&t.path, &final_path)?;
                return Ok(Some(final_path));
            }
            _ => bail!("unknown transfer phase"),
        }
        Ok(None)
    }
}
impl Drop for Receiver {
    fn drop(&mut self) {
        for t in self.incoming.values() {
            let _ = std::fs::remove_file(&t.path);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn traversal_rejected() {
        for n in ["../x", "/tmp/x", "a\\b", ".", "\n"] {
            assert!(safe_name(n).is_err())
        }
    }
    #[test]
    fn streaming_integrity_and_cleanup() {
        let root = std::env::temp_dir().join(format!("unity-transfer-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let mut r = Receiver::new(root.clone()).unwrap();
        let id = "01".repeat(16);
        let hash = format!("{:x}", Sha256::digest(b"hello"));
        r.event(&json!({"phase":"begin","id":id,"name":"hello.txt","size":5,"sha256":hash}))
            .unwrap();
        r.event(&json!({"phase":"chunk","id":id,"data":STANDARD.encode(b"hello")}))
            .unwrap();
        let p = r.event(&json!({"phase":"end","id":id})).unwrap().unwrap();
        assert_eq!(std::fs::read(p).unwrap(), b"hello");
        drop(r);
        std::fs::remove_dir_all(root).unwrap();
    }
}
