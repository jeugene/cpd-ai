use cpd_ai_agents::agents::cards_agent;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let filter = tracing_subscriber::EnvFilter::try_from_env("LOG_LEVEL")
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    tracing_subscriber::fmt().with_env_filter(filter).init();

    let out = cards_agent::run().await?;
    println!("Done → {}", out.display());
    Ok(())
}
