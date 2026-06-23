package com.cloudpid.ai.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class RagGeneratorFactory {

    private static final Logger log = LoggerFactory.getLogger(RagGeneratorFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagGeneratorFactory() {}

    public static RagGenerator create() {
        AppConfig cfg = AppConfigFactory.get();
        String provider = cfg.agentsProvider().toLowerCase();
        log.info("Using RAG generator provider: {}", provider);
        return switch (provider) {
            case "anthropic" -> anthropic(cfg);
            case "openai"    -> openai(cfg, "openai");
            case "copilot"   -> openai(cfg, "copilot");
            case "bedrock"   -> bedrock(cfg);
            default -> throw new IllegalArgumentException(
                "Unknown provider '" + provider + "'. Valid: anthropic, openai, copilot, bedrock");
        };
    }

    private static RagGenerator anthropic(AppConfig cfg) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        String model = cfg.agentsAnthropicModel();
        return (system, user) -> {
            log.debug("Generating with Anthropic model={}", model);
            return client.messages()
                .create(MessageCreateParams.builder()
                    .model(model).maxTokens(2048L)
                    .system(system).addUserMessage(user)
                    .build())
                .content().stream()
                .filter(b -> b.text().isPresent())
                .map(b -> b.text().get().text())
                .collect(Collectors.joining());
        };
    }

    private static RagGenerator bedrock(AppConfig cfg) {
        BedrockRuntimeClient client = BedrockRuntimeClient.builder()
            .region(Region.of(cfg.awsRegion()))
            .build();
        String model = cfg.agentsBedrockModel();
        return (system, user) -> {
            log.debug("Generating with Bedrock model={}", model);
            try {
                Map<String, Object> body = Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", 2048,
                    "system", system,
                    "messages", List.of(Map.of("role", "user", "content", user))
                );
                JsonNode result = MAPPER.readTree(
                    client.invokeModel(InvokeModelRequest.builder()
                        .modelId(model)
                        .contentType("application/json")
                        .accept("application/json")
                        .body(SdkBytes.fromUtf8String(MAPPER.writeValueAsString(body)))
                        .build())
                    .body().asUtf8String());
                JsonNode text = result.path("content").path(0).path("text");
                if (text.isMissingNode() || text.isNull()) {
                    throw new IllegalStateException("Unexpected Bedrock response: " + result);
                }
                return text.asText();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static RagGenerator openai(AppConfig cfg, String name) {
        boolean isCopilot = "copilot".equals(name);
        String baseUrl = isCopilot ? "https://models.inference.ai.azure.com" : "https://api.openai.com";
        String model = isCopilot ? cfg.agentsCopilotModel() : cfg.agentsOpenaiModel();
        String apiKey = isCopilot
            ? requireEnv("GITHUB_TOKEN", "GITHUB_TOKEN env var required for copilot provider")
            : requireEnv("OPENAI_API_KEY", "OPENAI_API_KEY env var required for openai provider");
        HttpClient http = HttpClient.newHttpClient();
        return (system, user) -> {
            log.debug("Generating with OpenAI-compatible model={} url={}", model, baseUrl);
            try {
                Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 2048,
                    "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                    )
                );
                HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException(
                        "OpenAI API error " + response.statusCode() + ": " + response.body());
                }
                JsonNode content = MAPPER.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
                if (content.isMissingNode() || content.isNull()) {
                    throw new IllegalStateException("Unexpected OpenAI response: " + response.body());
                }
                return content.asText();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HTTP request interrupted", e);
            }
        };
    }

    private static String requireEnv(String name, String message) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) throw new IllegalStateException(message);
        return val;
    }
}
