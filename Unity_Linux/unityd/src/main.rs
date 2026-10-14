use anyhow::Result;
use clap::Parser;
#[derive(Parser)]
#[command(version, about = "Unity independent companion daemon")]
struct Args {
    #[arg(long)]
    state_dir: Option<std::path::PathBuf>,
    #[arg(long, default_value = "0.0.0.0")]
    bind: String,
    #[arg(long,default_value_t=unity_protocol_port())]
    port: u16,
}
fn unity_protocol_port() -> u16 {
    17161
}
#[tokio::main(worker_threads = 2)]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "unityd=info,unity_core=info".into()),
        )
        .init();
    let a = Args::parse();
    let engine = unity_core::Engine::new(
        a.state_dir
            .unwrap_or_else(unity_security::default_state_dir),
        a.port,
    )?;
    let _bus = unity_ipc::serve(engine.clone()).await?;
    tracing::info!(
        port = a.port,
        "Unity daemon ready; pairing is closed until requested through D-Bus"
    );
    tokio::select! {result=engine.clone().serve(&a.bind)=>result?,_=tokio::signal::ctrl_c()=>{},_=terminate()=>{}}
    engine.shutdown_calls().await;
    engine.shutdown_screens().await;
    engine.shutdown_audio().await;
    Ok(())
}
async fn terminate() {
    if let Ok(mut signal) =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
    {
        signal.recv().await;
    } else {
        std::future::pending::<()>().await;
    }
}
