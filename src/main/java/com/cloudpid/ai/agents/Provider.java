package com.cloudpid.ai.agents;

import java.util.List;
import java.util.Map;

/** LLM provider abstraction — decouples the agent loop from the underlying API. */
public interface Provider {

    /** Send the next turn to the model and return a normalised response. */
    AgentResponse nextTurn();

    /** Feed tool results back so the provider can append them to its message history. */
    void submitToolResults(List<Map.Entry<ToolCall, String>> results);
}
