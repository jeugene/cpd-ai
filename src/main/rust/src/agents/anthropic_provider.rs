use crate::agents::provider::{AgentResponse, Provider, ToolCall};
use anyhow::{anyhow, Result};
use async_trait::async_trait;
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use tracing::debug;

const API_URL: &str = "https://api.anthropic.com/v1/messages";
const DEFAULT_MODEL: &str = "claude-sonnet-4-6";
const MAX_TOKENS: u64 = 16000;
const API_VERSION: &str = "2023-06-01";

pub struct AnthropicProvider {
    client: Client,
    api_key: String,
    model: String,
    system: String,
    tools: Vec<Value>,
    messages: Vec<Value>,
}

impl AnthropicProvider {
    pub fn new(
        api_key: String,
        model: impl Into<String>,
        system: impl Into<String>,
        kickoff: impl Into<String>,
        custom_tools: Vec<Value>,
    ) -> Self {
        let mut tools = vec![json!({"type": "web_search_20260209", "name": "web_search"})];
        tools.extend(custom_tools);

        Self {
            client: Client::new(),
            api_key,
            model: model.into(),
            system: system.into(),
            tools,
            messages: vec![json!({"role": "user", "content": kickoff.into()})],
        }
    }

    pub fn from_env(
        system: impl Into<String>,
        kickoff: impl Into<String>,
        custom_tools: Vec<Value>,
    ) -> Result<Self> {
        let api_key = std::env::var("ANTHROPIC_API_KEY")
            .map_err(|_| anyhow!("ANTHROPIC_API_KEY environment variable not set"))?;
        Ok(Self::new(api_key, DEFAULT_MODEL, system, kickoff, custom_tools))
    }
}

#[async_trait]
impl Provider for AnthropicProvider {
    async fn next_turn(&mut self) -> Result<AgentResponse> {
        let body = json!({
            "model": self.model,
            "max_tokens": MAX_TOKENS,
            "thinking": {"type": "adaptive"},
            "system": self.system,
            "tools": self.tools,
            "messages": self.messages,
        });

        let response = self
            .client
            .post(API_URL)
            .header("x-api-key", &self.api_key)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .json(&body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let text = response.text().await.unwrap_or_default();
            return Err(anyhow!("Anthropic API error {}: {}", status, text));
        }

        let resp: Value = response.json().await?;
        let stop_reason = resp["stop_reason"]
            .as_str()
            .unwrap_or("end_turn")
            .to_string();
        debug!(stop_reason = %stop_reason);

        // Append assistant turn to history (full content including thinking blocks).
        self.messages
            .push(json!({"role": "assistant", "content": resp["content"].clone()}));

        let tool_calls = resp["content"]
            .as_array()
            .map(|arr| {
                arr.iter()
                    .filter(|b| b["type"].as_str() == Some("tool_use"))
                    .map(|b| ToolCall {
                        id: b["id"].as_str().unwrap_or("").to_string(),
                        name: b["name"].as_str().unwrap_or("").to_string(),
                        input: b["input"]
                            .as_object()
                            .map(|m| {
                                m.iter()
                                    .map(|(k, v)| (k.clone(), v.clone()))
                                    .collect::<HashMap<_, _>>()
                            })
                            .unwrap_or_default(),
                    })
                    .collect()
            })
            .unwrap_or_default();

        Ok(AgentResponse {
            stop_reason,
            tool_calls,
        })
    }

    async fn submit_tool_results(&mut self, results: Vec<(ToolCall, String)>) -> Result<()> {
        let content: Vec<Value> = results
            .into_iter()
            .map(|(tc, result)| {
                json!({
                    "type": "tool_result",
                    "tool_use_id": tc.id,
                    "content": result,
                })
            })
            .collect();
        self.messages
            .push(json!({"role": "user", "content": content}));
        Ok(())
    }
}
