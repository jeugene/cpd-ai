package com.cloudpid.ai.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CardsAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── MockProvider ─────────────────────────────────────────────────────────────

    static class MockProvider implements Provider {
        private final Iterator<AgentResponse> responses;
        int turnCount = 0;
        final List<List<Map.Entry<ToolCall, String>>> submittedResults = new ArrayList<>();

        MockProvider(List<AgentResponse> responses) {
            this.responses = responses.iterator();
        }

        @Override
        public AgentResponse nextTurn() {
            turnCount++;
            return responses.next();
        }

        @Override
        public void submitToolResults(List<Map.Entry<ToolCall, String>> results) {
            submittedResults.add(new ArrayList<>(results));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static AgentResponse makeToolResponse(List<Map<String, Object>> cards) throws Exception {
        ToolCall tc = new ToolCall("toolu_test_001", "save_top_cards",
            Map.of("cards_json", MAPPER.writeValueAsString(cards)));
        return new AgentResponse("tool_use", List.of(tc));
    }

    private static AgentResponse makeEndResponse() {
        return new AgentResponse("end_turn");
    }

    private static List<Map<String, Object>> sampleCards() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", "Card " + i);
            card.put("issuer", "Test Bank");
            card.put("card_type", "Cash Back");
            card.put("apr", Map.of("min", 15.0 + i, "max", 21.0 + i));
            card.put("annual_fee", (double) (i * 50));
            card.put("rewards_rate", "2% cash back");
            card.put("sign_up_bonus", "$200 after $500 spend");
            card.put("key_features", List.of("No foreign fees"));
            card.put("rewards_score", 20.0 - i);
            card.put("bonus_score", 15.0 - i);
            cards.add(card);
        }
        return cards;
    }

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    void run_createsOutputFile(@TempDir Path tempDir) throws Exception {
        var provider = new MockProvider(List.of(makeToolResponse(sampleCards()), makeEndResponse()));
        Path result = CardsAgent.run(provider, tempDir);
        assertEquals(tempDir.resolve("top-credit-cards.json"), result);
        assertTrue(Files.exists(result));
    }

    @Test
    void run_outputHasExpectedKeys(@TempDir Path tempDir) throws Exception {
        List<Map<String, Object>> cards = sampleCards();
        var provider = new MockProvider(List.of(makeToolResponse(cards), makeEndResponse()));
        Path result = CardsAgent.run(provider, tempDir);
        JsonNode json = MAPPER.readTree(result.toFile());
        assertTrue(json.has("generated_at"));
        assertTrue(json.has("top_cards"));
        assertEquals(cards.size(), json.get("total").asInt());
    }

    @Test
    void run_outputCardsAreRanked(@TempDir Path tempDir) throws Exception {
        var provider = new MockProvider(List.of(makeToolResponse(sampleCards()), makeEndResponse()));
        CardsAgent.run(provider, tempDir);
        JsonNode topCards = MAPPER.readTree(tempDir.resolve("top-credit-cards.json").toFile())
            .get("top_cards");

        assertEquals(1, topCards.get(0).get("rank").asInt());
        assertEquals(sampleCards().size(), topCards.get(topCards.size() - 1).get("rank").asInt());

        double prev = Double.MAX_VALUE;
        for (JsonNode card : topCards) {
            double score = card.get("score").asDouble();
            assertTrue(score <= prev, "Cards must be sorted by score descending");
            prev = score;
        }
    }

    @Test
    void run_callsProviderTwice(@TempDir Path tempDir) throws Exception {
        var provider = new MockProvider(List.of(makeToolResponse(sampleCards()), makeEndResponse()));
        CardsAgent.run(provider, tempDir);
        assertEquals(2, provider.turnCount, "tool_use response + end_turn = 2 turns");
    }

    @Test
    void run_submitsToolResult(@TempDir Path tempDir) throws Exception {
        var provider = new MockProvider(List.of(makeToolResponse(sampleCards()), makeEndResponse()));
        CardsAgent.run(provider, tempDir);
        assertEquals(1, provider.submittedResults.size());
        assertEquals("save_top_cards", provider.submittedResults.get(0).get(0).getKey().name());
    }

    @Test
    void run_raisesIfSaveNeverCalled(@TempDir Path tempDir) {
        var provider = new MockProvider(List.of(makeEndResponse()));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> CardsAgent.run(provider, tempDir));
        assertTrue(ex.getMessage().contains("without writing"));
    }
}
