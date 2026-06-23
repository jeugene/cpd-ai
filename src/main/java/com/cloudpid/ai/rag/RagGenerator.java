package com.cloudpid.ai.rag;

/** Single-turn generation interface for RAG. One implementation per LLM provider. */
@FunctionalInterface
public interface RagGenerator {
    String generate(String systemPrompt, String userMessage);
}
