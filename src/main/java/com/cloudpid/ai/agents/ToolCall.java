package com.cloudpid.ai.agents;

import java.util.Map;

/** Normalised representation of a model tool invocation. */
public record ToolCall(String id, String name, Map<String, Object> input) {}
