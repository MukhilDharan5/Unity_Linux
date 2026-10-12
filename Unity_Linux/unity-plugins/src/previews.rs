//! Bounded JPEG previews shared by photos and media, without GUI dependencies.
use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::Value;

pub const MAX_BYTES: usize = 32 * 1024;
pub const MAX_EDGE: u64 = 320;

pub fn validate(image: &Value) -> Result<()> {
    if image["mime"] != "image/jpeg" {
        bail!("preview must be JPEG")
    }
    let width = image["width"].as_u64().context("missing preview width")?;
    let height = image["height"].as_u64().context("missing preview height")?;
    if !(1..=MAX_EDGE).contains(&width) || !(1..=MAX_EDGE).contains(&height) {
        bail!("preview dimensions exceed limit")
    }
    let encoded = image["data"].as_str().context("missing preview bytes")?;
    if encoded.len() > MAX_BYTES.div_ceil(3) * 4 {
        bail!("preview exceeds byte limit")
    }
    let bytes = STANDARD.decode(encoded)?;
    if bytes.len() > MAX_BYTES || jpeg_dimensions(&bytes)? != (width, height) {
        bail!("preview dimensions do not match JPEG")
    }
    Ok(())
}

// Inspect the JPEG header before clients decode it. Only ordinary 8-bit baseline
// or progressive JPEG is accepted; huge dimensions cannot hide behind metadata.
fn jpeg_dimensions(bytes: &[u8]) -> Result<(u64, u64)> {
    if !bytes.starts_with(&[0xff, 0xd8]) || !bytes.ends_with(&[0xff, 0xd9]) {
        bail!("invalid JPEG boundaries")
    }
    let mut offset = 2;
    let mut dimensions = None;
    while offset + 4 <= bytes.len() {
        if bytes[offset] != 0xff {
            bail!("invalid JPEG marker")
        }
        while bytes.get(offset) == Some(&0xff) {
            offset += 1;
        }
        let marker = *bytes.get(offset).context("truncated JPEG marker")?;
        offset += 1;
        let length = bytes
            .get(offset..offset + 2)
            .context("truncated JPEG segment")?;
        let length = u16::from_be_bytes([length[0], length[1]]) as usize;
        if length < 2 || offset + length > bytes.len() {
            bail!("invalid JPEG segment length")
        }
        if (0xc0..=0xcf).contains(&marker) && ![0xc4, 0xc8, 0xcc].contains(&marker) {
            if ![0xc0, 0xc2].contains(&marker) || dimensions.is_some() || length < 8 {
                bail!("unsupported JPEG frame")
            }
            let frame = &bytes[offset..offset + length];
            if frame[2] != 8 {
                bail!("unsupported JPEG sample depth")
            }
            let h = u16::from_be_bytes([frame[3], frame[4]]) as u64;
            let w = u16::from_be_bytes([frame[5], frame[6]]) as u64;
            dimensions = Some((w, h));
        }
        if marker == 0xda {
            return dimensions.context("missing JPEG dimensions");
        }
        offset += length;
    }
    bail!("missing JPEG scan")
}

pub fn validate_result(feature: &str, action: &str, value: &Value) -> Result<()> {
    if feature == "photos" && action == "preview" {
        if !unity_protocol::valid_id(super::text(value, "id", 32)?) {
            bail!("invalid photo selection")
        }
        validate(&value["preview"])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn image(width: u16) -> Value {
        let mut bytes = vec![0xff, 0xd8, 0xff, 0xc0, 0, 11, 8, 0, 1];
        bytes.extend(width.to_be_bytes());
        bytes.extend([
            1, 1, 0x11, 0, 0xff, 0xda, 0, 8, 1, 1, 0, 0, 63, 0, 0xff, 0xd9,
        ]);
        json!({"mime":"image/jpeg", "width":width,"height":1,"data":STANDARD.encode(bytes)})
    }
    #[test]
    fn prevents_preview_bombs_and_false_dimensions() {
        assert!(validate(&image(256)).is_ok());
        assert!(validate(&image(8192)).is_err());
        let mut wrong = image(8192);
        wrong["width"] = json!(256);
        assert!(validate(&wrong).is_err());
        let mut oversized = image(256);
        oversized["data"] = json!("A".repeat(50000));
        assert!(validate(&oversized).is_err());
    }
    #[test]
    fn rejects_bad_photo_requests_and_results() {
        let p = super::super::plugin("photos").unwrap();
        assert!(p
            .command("preview", &json!({"id":"a".repeat(32),"edge":256}))
            .is_ok());
        assert!(p
            .command("preview", &json!({"id":"../a","edge":256}))
            .is_err());
        assert!(p
            .command("preview", &json!({"id":"a".repeat(32),"edge":1024}))
            .is_err());
        assert!(validate_result(
            "photos",
            "preview",
            &json!({"id":"a".repeat(32),"preview":image(256)})
        )
        .is_ok());
        assert!(validate_result("photos", "preview", &json!({"id":"a".repeat(32)})).is_err());
    }
}
