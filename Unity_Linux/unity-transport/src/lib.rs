//! Interchangeable transport contract and bounded message framing.
pub mod bluetooth;
pub mod network;
use anyhow::{bail, Result};
use async_trait::async_trait;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use unity_protocol::{Message, MAX_FRAME};
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Lan,
    Ble,
}
#[derive(Debug, Clone, Copy)]
pub enum Workload {
    Control,
    Bulk,
}
pub fn select(available: &[Kind], work: Workload) -> Option<Kind> {
    match work {
        Workload::Bulk => available.iter().find(|k| **k == Kind::Lan).copied(),
        Workload::Control => available
            .iter()
            .find(|k| **k == Kind::Ble)
            .or_else(|| available.iter().find(|k| **k == Kind::Lan))
            .copied(),
    }
}
#[async_trait]
pub trait Transport: Send + Sync {
    fn kind(&self) -> Kind;
    async fn send(&mut self, message: &Message) -> Result<()>;
    async fn receive(&mut self) -> Result<Message>;
}
/// Retains header and payload progress when a select branch cancels receive().
#[derive(Default)]
pub struct FrameReader {
    header: [u8; 4],
    header_read: usize,
    payload: Vec<u8>,
    payload_read: usize,
}
impl FrameReader {
    pub async fn receive<R: AsyncRead + Unpin>(&mut self, r: &mut R) -> Result<Message> {
        while self.header_read < 4 {
            let n = r.read(&mut self.header[self.header_read..]).await?;
            if n == 0 {
                bail!("connection closed during frame header")
            }
            self.header_read += n;
        }
        if self.payload.is_empty() {
            let len = u32::from_be_bytes(self.header) as usize;
            if len == 0 || len > MAX_FRAME {
                bail!("frame size outside allowed range")
            }
            self.payload.resize(len, 0);
        }
        while self.payload_read < self.payload.len() {
            let n = r.read(&mut self.payload[self.payload_read..]).await?;
            if n == 0 {
                bail!("connection closed during frame payload")
            }
            self.payload_read += n;
        }
        let message = serde_json::from_slice(&self.payload);
        self.header_read = 0;
        self.payload_read = 0;
        self.payload.clear();
        Ok(message?)
    }
}
/// One-shot convenience reader. Select loops must retain a FrameReader instead.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> Result<Message> {
    FrameReader::default().receive(r).await
}
pub async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, m: &Message) -> Result<()> {
    let b = serde_json::to_vec(m)?;
    if b.len() > MAX_FRAME {
        bail!("frame too large")
    }
    w.write_u32(b.len() as u32).await?;
    w.write_all(&b).await?;
    w.flush().await?;
    Ok(())
}
pub fn backoff(attempt: u32) -> Duration {
    Duration::from_secs(1u64 << attempt.min(6))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn framing_and_limit() {
        let (mut a, mut b) = tokio::io::duplex(1024);
        write_frame(&mut a, &Message::Ping).await.unwrap();
        assert!(matches!(read_frame(&mut b).await.unwrap(), Message::Ping));
        a.write_u32((MAX_FRAME + 1) as u32).await.unwrap();
        assert!(read_frame(&mut b).await.is_err());
    }
    #[tokio::test]
    async fn interrupted_header_and_payload_resume_without_losing_bytes() {
        let (mut a, mut b) = tokio::io::duplex(1024);
        let payload = serde_json::to_vec(&Message::Ping).unwrap();
        let header = (payload.len() as u32).to_be_bytes();
        let mut reader = FrameReader::default();
        a.write_all(&header[..2]).await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(5), reader.receive(&mut b))
                .await
                .is_err()
        );
        a.write_all(&header[2..]).await.unwrap();
        a.write_all(&payload[..3]).await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(5), reader.receive(&mut b))
                .await
                .is_err()
        );
        a.write_all(&payload[3..]).await.unwrap();
        assert!(matches!(
            reader.receive(&mut b).await.unwrap(),
            Message::Ping
        ));
        write_frame(&mut a, &Message::Pong).await.unwrap();
        assert!(matches!(
            reader.receive(&mut b).await.unwrap(),
            Message::Pong
        ));
    }
    #[test]
    fn bulk_never_uses_ble() {
        assert_eq!(select(&[Kind::Ble], Workload::Bulk), None);
        assert_eq!(
            select(&[Kind::Lan, Kind::Ble], Workload::Control),
            Some(Kind::Ble)
        );
        assert_eq!(backoff(99).as_secs(), 64)
    }
}
