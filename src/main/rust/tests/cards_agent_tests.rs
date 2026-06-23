use async_trait::async_trait;
use cpd_ai_agents::agents::cards_agent::run_with_provider;
use cpd_ai_agents::agents::provider::{AgentResponse, Provider, ToolCall};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tempfile::TempDir;

// ── MockProvider ──────────────────────────────────────────────────────────────

struct MockProvider {
    responses: Vec<AgentResponse>,
    index: usize,
    pub turn_count: Arc<Mutex<usize>>,
    pub submitted_results: Arc<Mutex<Vec<Vec<(ToolCall, String)>>>>,
}

impl MockProvider {
    fn new(responses: Vec<AgentResponse>) -> Self {
        Self {
            responses,
            index: 0,
            turn_count: Arc::new(Mutex::new(0)),
            submitted_results: Arc::new(Mutex::new(vec![])),
        }
    }
}

#[async_trait]
impl Provider for MockProvider {
    async fn next_turn(&mut self) -> anyhow::Result<AgentResponse> {
        *self.turn_count.lock().unwrap() += 1;
        let resp = self.responses[self.index].clone();
        self.index += 1;
        Ok(resp)
    }

    async fn submit_tool_results(
        &mut self,
        results: Vec<(ToolCall, String)>,
    ) -> anyhow::Result<()> {
        self.submitted_results.lock().unwrap().push(results);
        Ok(())
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────

fn sample_cards() -> Vec<Value> {
    (0..8_usize)
        .map(|i| {
            json!({
                "name": format!("Card {i}"),
                "issuer": "Test Bank",
                "card_type": "Cash Back",
                "apr": {"min": 15.0 + i as f64, "max": 21.0 + i as f64},
                "annual_fee": (i * 50) as f64,
                "rewards_rate": "2% cash back",
                "sign_up_bonus": "$200 after $500 spend",
                "key_features": ["No foreign fees"],
                "rewards_score": 20.0 - i as f64,
                "bonus_score": 15.0 - i as f64,
            })
        })
        .collect()
}

fn make_tool_response(cards: &[Value]) -> AgentResponse {
    let mut input = HashMap::new();
    input.insert(
        "cards_json".to_string(),
        json!(serde_json::to_string(cards).unwrap()),
    );
    let tc = ToolCall {
        id: "toolu_test_001".to_string(),
        name: "save_top_cards".to_string(),
        input,
    };
    AgentResponse::tool_use(vec![tc])
}

fn make_end_response() -> AgentResponse {
    AgentResponse::end_turn()
}

// ── tests ─────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn run_creates_output_file() {
    let temp = TempDir::new().unwrap();
    let provider = MockProvider::new(vec![make_tool_response(&sample_cards()), make_end_response()]);
    let result = run_with_provider(Box::new(provider), temp.path()).await.unwrap();
    assert_eq!(result, temp.path().join("top-credit-cards.json"));
    assert!(result.exists());
}

#[tokio::test]
async fn run_output_has_expected_keys() {
    let temp = TempDir::new().unwrap();
    let cards = sample_cards();
    let provider = MockProvider::new(vec![make_tool_response(&cards), make_end_response()]);
    let result = run_with_provider(Box::new(provider), temp.path()).await.unwrap();
    let json: Value = serde_json::from_str(&std::fs::read_to_string(&result).unwrap()).unwrap();
    assert!(json.get("generated_at").is_some());
    assert!(json.get("top_cards").is_some());
    assert_eq!(json["total"].as_u64().unwrap(), cards.len() as u64);
}

#[tokio::test]
async fn run_output_cards_are_ranked() {
    let temp = TempDir::new().unwrap();
    let provider = MockProvider::new(vec![make_tool_response(&sample_cards()), make_end_response()]);
    run_with_provider(Box::new(provider), temp.path()).await.unwrap();

    let json: Value = serde_json::from_str(
        &std::fs::read_to_string(temp.path().join("top-credit-cards.json")).unwrap(),
    )
    .unwrap();
    let top = json["top_cards"].as_array().unwrap();

    assert_eq!(top[0]["rank"].as_u64().unwrap(), 1);
    assert_eq!(top.last().unwrap()["rank"].as_u64().unwrap(), top.len() as u64);

    let mut prev = f64::MAX;
    for card in top {
        let score = card["score"].as_f64().unwrap();
        assert!(score <= prev, "Cards must be sorted by score descending");
        prev = score;
    }
}

#[tokio::test]
async fn run_calls_provider_twice() {
    let temp = TempDir::new().unwrap();
    let provider = MockProvider::new(vec![make_tool_response(&sample_cards()), make_end_response()]);
    let turn_count = Arc::clone(&provider.turn_count);
    run_with_provider(Box::new(provider), temp.path()).await.unwrap();
    assert_eq!(*turn_count.lock().unwrap(), 2, "tool_use response + end_turn = 2 turns");
}

#[tokio::test]
async fn run_submits_tool_result() {
    let temp = TempDir::new().unwrap();
    let provider = MockProvider::new(vec![make_tool_response(&sample_cards()), make_end_response()]);
    let submitted = Arc::clone(&provider.submitted_results);
    run_with_provider(Box::new(provider), temp.path()).await.unwrap();

    let results = submitted.lock().unwrap();
    assert_eq!(results.len(), 1);
    assert_eq!(results[0][0].0.name, "save_top_cards");
}

#[tokio::test]
async fn run_raises_if_save_never_called() {
    let temp = TempDir::new().unwrap();
    let provider = MockProvider::new(vec![make_end_response()]);
    let err = run_with_provider(Box::new(provider), temp.path())
        .await
        .unwrap_err();
    assert!(err.to_string().contains("without writing"));
}
