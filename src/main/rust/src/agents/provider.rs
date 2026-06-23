use anyhow::Result;
use async_trait::async_trait;
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub input: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone)]
pub struct AgentResponse {
    pub stop_reason: String,
    pub tool_calls: Vec<ToolCall>,
}

impl AgentResponse {
    pub fn end_turn() -> Self {
        Self {
            stop_reason: "end_turn".to_string(),
            tool_calls: vec![],
        }
    }

    pub fn tool_use(tool_calls: Vec<ToolCall>) -> Self {
        Self {
            stop_reason: "tool_use".to_string(),
            tool_calls,
        }
    }
}

#[async_trait]
pub trait Provider: Send {
    async fn next_turn(&mut self) -> Result<AgentResponse>;
    async fn submit_tool_results(&mut self, results: Vec<(ToolCall, String)>) -> Result<()>;
}
