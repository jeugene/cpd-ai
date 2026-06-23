package com.cloudpid.ai.rag;

import com.cloudpid.ai.config.AppConfig;
import com.cloudpid.ai.config.AppConfigFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bedrock Titan Embeddings v2 wrapper.
 * Mirrors Python {@code rag/embedder.py}.
 */
public final class Embedder {

    private static final Logger log = LoggerFactory.getLogger(Embedder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final int dims;

    public Embedder(BedrockRuntimeClient client, String modelId, int dims) {
        this.client = client;
        this.modelId = modelId;
        this.dims = dims;
    }

    public static Embedder create() {
        AppConfig cfg = AppConfigFactory.get();
        BedrockRuntimeClient client = BedrockRuntimeClient.builder()
            .region(Region.of(cfg.awsRegion()))
            .build();
        return new Embedder(client, cfg.ragEmbedModel(), cfg.ragEmbedDims());
    }

    /**
     * Embed a single text string using Titan Embeddings v2.
     * Mirrors Python {@code embed_text(text)}.
     */
    public List<Float> embedText(String text) {
        try {
            String body = MAPPER.writeValueAsString(Map.of("inputText", text, "dimensions", dims));
            InvokeModelResponse response = client.invokeModel(InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(body))
                .build());
            JsonNode result = MAPPER.readTree(response.body().asUtf8String());
            JsonNode embedding = result.path("embedding");
            if (embedding.isMissingNode() || !embedding.isArray()) {
                throw new IllegalStateException("Unexpected Bedrock embedding response: " + result);
            }
            List<Float> vector = new ArrayList<>();
            for (JsonNode val : embedding) {
                vector.add(val.floatValue());
            }
            log.debug("Embedded {} chars → {}-dim vector", text.length(), vector.size());
            return vector;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
