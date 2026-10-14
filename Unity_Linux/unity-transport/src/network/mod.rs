use crate::{write_frame, FrameReader, Kind, Transport};
use anyhow::Result;
use async_trait::async_trait;
use unity_protocol::Message;
pub struct LanTransport {
    stream: crate::SecureStream,
    reader: FrameReader,
    kind: Kind,
}
impl LanTransport {
    pub fn new(stream: crate::SecureStream) -> Self {
        Self {
            stream,
            reader: FrameReader::default(),
            kind: Kind::Lan,
        }
    }
    pub fn bluetooth(stream: crate::SecureStream) -> Self {
        Self {
            stream,
            reader: FrameReader::default(),
            kind: Kind::Bluetooth,
        }
    }
}
#[async_trait]
impl Transport for LanTransport {
    fn kind(&self) -> Kind {
        self.kind
    }
    async fn send(&mut self, m: &Message) -> Result<()> {
        write_frame(&mut self.stream, m).await
    }
    async fn receive(&mut self) -> Result<Message> {
        self.reader.receive(&mut self.stream).await
    }
}
