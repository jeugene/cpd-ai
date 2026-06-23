package com.cloudpid.ai.agents;

import java.util.List;

/** Normalised response across all providers. */
public record AgentResponse(String stopReason, List<ToolCall> toolCalls) {

    /** Convenience constructor for terminal responses with no tool calls. */
    public AgentResponse(String stopReason) {
        this(stopReason, List.of());
    }
}
