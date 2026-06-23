package com.cloudpid.ai.rag;

import java.util.List;
import java.util.Map;

/** A text chunk paired with its embedding vector and source metadata. */
public record VectorChunk(String key, List<Float> vector, Map<String, Object> metadata) {}
