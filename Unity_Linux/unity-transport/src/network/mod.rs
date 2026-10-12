use crate::{write_frame, FrameReader, Kind, Transport};
use anyhow::Result;
use async_trait::async_trait;
use tokio::net::TcpStream;
use tokio_rustls::server::TlsStream;
use unity_protocol::Message;
pub struct LanTransport {
    stream: TlsStream<TcpStream>,
    reader: FrameReader,
}
impl LanTransport {
    pub fn new(stream: TlsStream<TcpStream>) -> Self {
        Self {
            stream,
            reader: FrameReader::default(),
        }
    }
}
#[async_trait]
impl Transport for LanTransport {
    fn kind(&self) -> Kind {
        Kind::Lan
    }
    async fn send(&mut self, m: &Message) -> Result<()> {
        write_frame(&mut self.stream, m).await
    }
    async fn receive(&mut self) -> Result<Message> {
        self.reader.receive(&mut self.stream).await
    }
}
