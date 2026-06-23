package com.cloudpid.ai.agents;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

/** Anthropic API provider — Anthropic API + server-side web_search_20260209. */
final class AnthropicProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_TOKENS = 16000L;

    private final AnthropicClient client;
    private final String model;
    private final String system;
    private final List<Tool> customTools;
    private final List<MessageParam> messages = new ArrayList<>();

    AnthropicProvider(AnthropicClient client, String model, String system,
                      String kickoff, List<Tool> customTools) {
        this.client = client;
        this.model = model;
        this.system = system;
        this.customTools = List.copyOf(customTools);
        messages.add(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(kickoff)
            .build());
    }

    static Provider create(String model, String system, String kickoff, List<Tool> customTools) {
        return new AnthropicProvider(AnthropicOkHttpClient.fromEnv(), model, system, kickoff, customTools);
    }

    @Override
    public AgentResponse nextTurn() {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .system(system)
            .addTool(WebSearchTool20260209.builder().build())
            .messages(messages);
        customTools.forEach(builder::addTool);

        Message response = client.messages().create(builder.build());
        log.debug("stop_reason={}", response.stopReason());

        messages.add(toAssistantParam(response));

        List<ToolCall> toolCalls = response.content().stream()
            .filter(b -> b.toolUse().isPresent())
            .map(b -> {
                ToolUseBlock tu = b.toolUse().get();
                return new ToolCall(tu.id(), tu.name(), parseInput(tu));
            })
            .collect(Collectors.toList());

        return new AgentResponse(stopReasonString(response.stopReason()), toolCalls);
    }

    @Override
    public void submitToolResults(List<Map.Entry<ToolCall, String>> results) {
        List<ContentBlockParam> blocks = results.stream()
            .map(e -> ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(e.getKey().id())
                    .content(e.getValue())
                    .build()))
            .collect(Collectors.toList());
        messages.add(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(blocks)
            .build());
    }

    private static MessageParam toAssistantParam(Message response) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.thinking().ifPresent(t ->
                blocks.add(ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder()
                        .thinking(t.thinking())
                        .signature(t.signature())
                        .build())));
            block.text().ifPresent(t ->
                blocks.add(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(t.text()).build())));
            block.toolUse().ifPresent(tu ->
                blocks.add(ContentBlockParam.ofToolUse(
                    ToolUseBlockParam.builder()
                        .id(tu.id())
                        .name(tu.name())
                        .input(tu._input())
                        .build())));
        }
        return MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(blocks)
            .build();
    }

    private static Map<String, Object> parseInput(ToolUseBlock tu) {
        try {
            return MAPPER.readValue(tu._input().toString(),
                new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stopReasonString(Optional<StopReason> opt) {
        StopReason sr = opt.orElse(null);
        if (StopReason.TOOL_USE.equals(sr)) return "tool_use";
        if (StopReason.PAUSE_TURN.equals(sr)) return "pause_turn";
        if (StopReason.MAX_TOKENS.equals(sr)) return "max_tokens";
        return "end_turn";
    }
}
