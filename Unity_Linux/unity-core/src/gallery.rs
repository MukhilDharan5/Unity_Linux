//! Correlate a clipboard-only download with its original operation and link.
use crate::clipboard::TemporaryFile;
use anyhow::{Context, Result};
use serde_json::Value;
use std::{
    collections::{HashMap, HashSet},
    os::unix::fs::DirBuilderExt,
    path::PathBuf,
    time::{Duration, Instant},
};

/// Gallery originals used as drag sources keep their human-readable filename.
/// Hard-link creation is exclusive, so an existing download can never be overwritten.
pub(super) fn original_name(path: &std::path::Path, id: &str) -> PathBuf {
    let Some(root) = path.parent() else {
        return path.to_owned();
    };
    let Some(name) = path
        .file_name()
        .and_then(|n| n.to_str())
        .and_then(|n| n.strip_prefix(&format!("{id}-")))
    else {
        return path.to_owned();
    };
    let base = std::path::Path::new(name);
    let stem = base.file_stem().and_then(|n| n.to_str()).unwrap_or(name);
    let extension = base
        .extension()
        .and_then(|n| n.to_str())
        .map(|n| format!(".{n}"))
        .unwrap_or_default();
    for index in 0..1000 {
        let target = root.join(if index == 0 {
            name.to_owned()
        } else {
            format!("{stem} ({index}){extension}")
        });
        match std::fs::hard_link(path, &target) {
            Ok(()) => {
                std::fs::remove_file(path).ok();
                return target;
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(_) => break, // The verified original remains usable on filesystems without hard links.
        }
    }
    path.to_owned()
}

pub(super) struct Downloads {
    receiver: unity_plugins::transfer::Receiver,
    active: HashSet<String>,
    ready: HashMap<String, (TemporaryFile, Instant)>,
}
impl Downloads {
    pub fn new(directory: PathBuf) -> Result<Self> {
        Ok(Self {
            receiver: unity_plugins::transfer::Receiver::new(directory)?,
            active: HashSet::new(),
            ready: HashMap::new(),
        })
    }
    pub fn event(&mut self, data: &Value, clipboard_request: bool) -> Result<bool> {
        let id = data["id"].as_str().context("Missing gallery transfer ID")?;
        let begin = data["phase"] == "begin" && clipboard_request;
        if !begin && !self.active.contains(id) {
            return Ok(false);
        }
        let received = self.receiver.event(data)?;
        if begin {
            self.active.insert(id.to_owned());
        }
        if let Some(path) = received {
            self.active.remove(id);
            self.prune();
            let mut file = TemporaryFile(path);
            let root = file.0.parent().context("Missing clipboard directory")?;
            let name = file
                .0
                .file_name()
                .and_then(|s| s.to_str())
                .and_then(|s| s.strip_prefix(&format!("{id}-")))
                .context("Invalid clipboard filename")?;
            let directory = root.join(id);
            std::fs::DirBuilder::new().mode(0o700).create(&directory)?;
            let target = directory.join(name);
            if let Err(error) = std::fs::rename(&file.0, &target) {
                let _ = std::fs::remove_dir(&directory);
                return Err(error.into());
            }
            file.0 = target;
            self.ready.insert(id.to_owned(), (file, Instant::now()));
        }
        if data["phase"] == "abort" {
            self.active.remove(id);
        }
        Ok(true)
    }
    pub fn take(&mut self, id: &str) -> Result<TemporaryFile> {
        self.ready
            .remove(id)
            .map(|(file, _)| file)
            .context("Clipboard download was not verified; update the phone companion and retry")
    }
    pub fn prune(&mut self) {
        self.ready
            .retain(|_, (_, created)| created.elapsed() < Duration::from_secs(120));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_names_do_not_replace_existing_downloads() {
        let root =
            std::env::temp_dir().join(format!("unity-gallery-name-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let source = root.join("transfer-தமிழ் # video.mp4");
        std::fs::write(root.join("தமிழ் # video.mp4"), b"existing").unwrap();
        std::fs::write(&source, b"original").unwrap();
        let renamed = original_name(&source, "transfer");
        assert_eq!(renamed.file_name().unwrap(), "தமிழ் # video (1).mp4");
        assert_eq!(std::fs::read(renamed).unwrap(), b"original");
        assert_eq!(
            std::fs::read(root.join("தமிழ் # video.mp4")).unwrap(),
            b"existing"
        );
        assert!(!source.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
}
