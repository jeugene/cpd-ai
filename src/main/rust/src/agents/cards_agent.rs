use crate::agents::anthropic_provider::AnthropicProvider;
use crate::agents::provider::{AgentResponse, Provider, ToolCall};
use crate::agents::ranker::rank_cards;
use anyhow::{anyhow, Result};
use chrono::Utc;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use tracing::{info, warn};

const MAX_TURNS: u32 = 30;

const SYSTEM: &str = r#"You are a credit card research agent. Follow these steps exactly:

1. Use web_search to find the current best US credit cards. Search separately for:
   - Best travel rewards credit cards (APR, annual fee, points per dollar)
   - Best cash-back credit cards (APR, annual fee, cash-back rate)
   - Best no-annual-fee credit cards (APR, rewards)
   Use at least 3 searches to cover all three categories.

2. Collect accurate data for 10–12 cards spanning all categories. For each card record:
   - name: full card name
   - issuer: bank or issuer name
   - card_type: one of "Travel", "Cash Back", "No Annual Fee", "Secured", "Business"
   - apr: {"min": <float>, "max": <float>} — current variable APR range
   - annual_fee: annual fee in USD (0 if none)
   - rewards_rate: concise description (e.g. "3x on dining, 2x on travel, 1x all else")
   - sign_up_bonus: concise description of the current welcome offer
   - key_features: list of 2–4 standout features
   - rewards_score: your estimate 0–25 of rewards value (25 = exceptional)
   - bonus_score: your estimate 0–20 of sign-up bonus value (20 = exceptional)

3. Call save_top_cards with a JSON array of the card objects.

Use only current, authoritative sources. Do not fabricate rates or offers."#;

const KICKOFF: &str = "Research and rank the best US credit cards available right now. \
Cover travel, cash-back, and no-annual-fee categories, then save the ranked results.";

fn save_top_cards_tool() -> Value {
    json!({
        "name": "save_top_cards",
        "description": "Persist the researched and ranked credit card data to disk. Call exactly once after you have gathered data for at least 10 cards.",
        "input_schema": {
            "type": "object",
            "properties": {
                "cards_json": {
                    "type": "string",
                    "description": "JSON array of credit card objects. Each must include: name (str), issuer (str), card_type (str), apr (object with 'min' and 'max' float keys), annual_fee (float), rewards_rate (str), sign_up_bonus (str), key_features (list[str]), rewards_score (float 0-25), bonus_score (float 0-20)."
                }
            },
            "required": ["cards_json"]
        }
    })
}

fn save_top_cards(tc: &ToolCall, data_dir: &Path) -> Result<String> {
    let cards_json = tc
        .input
        .get("cards_json")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("cards_json missing from tool input"))?;

    let payload: Value = serde_json::from_str(cards_json)?;
    let cards: Vec<Value> = match payload {
        Value::Array(arr) => arr,
        Value::Object(ref obj) => obj
            .get("cards")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default(),
        _ => return Err(anyhow!("Unexpected cards_json format")),
    };

    let ranked = rank_cards(cards);
    let count = ranked.len();

    let output = json!({
        "generated_at": Utc::now().to_rfc3339(),
        "source": "web_search",
        "total": count,
        "top_cards": ranked,
    });

    std::fs::create_dir_all(data_dir)?;
    let output_path = data_dir.join("top-credit-cards.json");
    std::fs::write(&output_path, serde_json::to_string_pretty(&output)?)?;
    info!("Saved {} ranked cards → {}", count, output_path.display());

    Ok(format!("Saved {} ranked cards to {}", count, output_path.display()))
}

pub fn default_data_dir() -> PathBuf {
    std::env::current_dir().unwrap_or_default().join("data")
}

pub async fn run() -> Result<PathBuf> {
    let provider = AnthropicProvider::from_env(
        SYSTEM,
        KICKOFF,
        vec![save_top_cards_tool()],
    )?;
    run_with_provider(Box::new(provider), &default_data_dir()).await
}

pub async fn run_with_provider(
    mut provider: Box<dyn Provider>,
    data_dir: &Path,
) -> Result<PathBuf> {
    let output_path = data_dir.join("top-credit-cards.json");

    for turn in 1..=MAX_TURNS {
        info!("Agent turn {}/{}", turn, MAX_TURNS);
        let response: AgentResponse = provider.next_turn().await?;
        info!(stop_reason = %response.stop_reason);

        match response.stop_reason.as_str() {
            "end_turn" => break,
            "pause_turn" => continue,
            "tool_use" => {
                let mut results: Vec<(ToolCall, String)> = Vec::new();
                for tc in &response.tool_calls {
                    if tc.name == "save_top_cards" {
                        results.push((tc.clone(), save_top_cards(tc, data_dir)?));
                    }
                }
                if !results.is_empty() {
                    provider.submit_tool_results(results).await?;
                } else {
                    warn!("tool_use stop but no recognized tool in response; stopping.");
                    break;
                }
            }
            _ => {}
        }
    }

    if !output_path.exists() {
        return Err(anyhow!(
            "Agent finished without writing {}",
            output_path.display()
        ));
    }

    Ok(output_path)
}
