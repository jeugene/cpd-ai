package com.cloudpid.ai.rag;

import com.cloudpid.ai.config.AppConfig;
import com.cloudpid.ai.config.AppConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * End-to-end RAG query pipeline: embed question → ANN retrieve → generate answer.
 * Mirrors Python {@code rag/pipeline.py}.
 */
public final class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);

    static final String SYSTEM_PROMPT =
        "You are a helpful assistant for a banking data platform. " +
        "Answer the user's question using only the provided context. " +
        "If the context does not contain enough information, say so clearly. " +
        "Do not fabricate facts.";

    private final Embedder embedder;
    private final S3VectorsClient s3Vectors;
    private final RagGenerator generator;
    private final int topK;
    private final String vectorBucket;

    public RagPipeline(Embedder embedder, S3VectorsClient s3Vectors,
                       RagGenerator generator, int topK, String vectorBucket) {
        this.embedder = embedder;
        this.s3Vectors = s3Vectors;
        this.generator = generator;
        this.topK = topK;
        this.vectorBucket = vectorBucket;
    }

    public static RagPipeline create() {
        AppConfig cfg = AppConfigFactory.get();
        S3VectorsClient s3v = S3VectorsClient.builder()
            .region(Region.of(cfg.awsRegion()))
            .build();
        return new RagPipeline(
            Embedder.create(),
            s3v,
            RagGeneratorFactory.create(),
            cfg.ragTopK(),
            cfg.ragVectorBucket()
        );
    }

    /**
     * Assemble the user message from a question and retrieved context chunks.
     * Pure function — no I/O. Mirrors Python {@code build_prompt(question, context_chunks)}.
     */
    public static String buildPrompt(String question, List<Map<String, Object>> contextChunks) {
        String contextText = contextChunks.stream()
            .map(c -> "[Source: " + c.getOrDefault("source_key", "unknown") +
                      " chunk " + c.getOrDefault("chunk_index", "?") +
                      "]\n" + c.getOrDefault("text", ""))
            .collect(Collectors.joining("\n\n---\n\n"));
        return "Context:\n" + contextText + "\n\nQuestion: " + question;
    }

    /**
     * End-to-end RAG query using the instance's configured topK and vectorBucket.
     * Mirrors Python {@code answer_question(question)}.
     */
    public String answerQuestion(String question) {
        return answerQuestion(question, topK, vectorBucket);
    }

    /**
     * End-to-end RAG query with explicit parameters.
     * Mirrors Python {@code answer_question(question, *, top_k, vector_bucket)}.
     */
    public String answerQuestion(String question, int topK, String vectorBucket) {
        log.info("RAG query: {} (top_k={})", question, topK);
        List<Float> queryVector = embedder.embedText(question);
        List<Map<String, Object>> chunks = VectorStore.queryChunks(queryVector, topK, vectorBucket, s3Vectors);
        log.debug("Retrieved {} context chunks", chunks.size());
        String userMessage = buildPrompt(question, chunks);
        return generator.generate(SYSTEM_PROMPT, userMessage);
    }
}
