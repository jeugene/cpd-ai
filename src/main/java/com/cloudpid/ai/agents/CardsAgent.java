package com.cloudpid.ai.agents;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Credit card research agent.
 *
 * Uses Claude with web search to fetch current card offers, ranks them,
 * and writes data/top-credit-cards.json.
 *
 * Usage:
 *   java -cp cpd-ai.jar com.cloudpid.ai.agents.CardsAgent
 *
 * Requires ANTHROPIC_API_KEY in the environment.
 */
public final class CardsAgent {

    private static final Logger log = LoggerFactory.getLogger(CardsAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String MODEL = "claude-sonnet-4-6";
    private static final int MAX_TURNS = 30;

    static final Path DEFAULT_DATA_DIR = Paths.get("data").toAbsolutePath();

    private static final Tool SAVE_TOP_CARDS_TOOL = Tool.builder()
        .name("save_top_cards")
        .description(
            "Persist the researched and ranked credit card data to disk. " +
            "Call exactly once after you have gathered data for at least 10 cards.")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("cards_json", JsonValue.from(Map.of(
                    "type", "string",
                    "description",
                    "JSON array of credit card objects. Each must include: " +
                    "name (str), issuer (str), card_type (str), " +
                    "apr (object with 'min' and 'max' float keys), " +
                    "annual_fee (float), rewards_rate (str), " +
                    "sign_up_bonus (str), key_features (list[str]), " +
                    "rewards_score (float 0-25), bonus_score (float 0-20)."
                )))
                .build())
            .required(List.of("cards_json"))
            .build())
        .build();

    private static final String SYSTEM = """
        You are a credit card research agent. Follow these steps exactly:

        1. Use web_search to find the current best US credit cards. Search separately for:
           - Best travel rewards credit cards (APR, annual fee, points per dollar)
           - Best cash-back credit cards (APR, annual fee, cash-back rate)
           - Best no-annual-fee credit cards (APR, rewards)
           Use at least 3 searches to cover all three categories.

        2. Collect accurate data for 10–12 cards spanning all categories. For each card record:
           - name: full card name
           - issuer: bank or issuer name
           - card_type: one of "Travel", "Cash Back", "No Annual Fee", "Secured", "Business"
           - apr: {"min": <float>, "max": <float>} — current variable APR range
           - annual_fee: annual fee in USD (0 if none)
           - rewards_rate: concise description (e.g. "3x on dining, 2x on travel, 1x all else")
           - sign_up_bonus: concise description of the current welcome offer
           - key_features: list of 2–4 standout features
           - rewards_score: your estimate 0–25 of rewards value (25 = exceptional)
           - bonus_score: your estimate 0–20 of sign-up bonus value (20 = exceptional)

        3. Call save_top_cards with a JSON array of the card objects.

        Use only current, authoritative sources. Do not fabricate rates or offers.
        """;

    private static final String KICKOFF =
        "Research and rank the best US credit cards available right now. " +
        "Cover travel, cash-back, and no-annual-fee categories, " +
        "then save the ranked results.";

    private CardsAgent() {}

    /** Run the agent and return the path to the written JSON file. */
    public static Path run() {
        Provider provider = AnthropicProvider.create(MODEL, SYSTEM, KICKOFF, List.of(SAVE_TOP_CARDS_TOOL));
        return run(provider, DEFAULT_DATA_DIR);
    }

    /** Package-private overload — injects provider and output directory for testing. */
    static Path run(Provider provider, Path dataDir) {
        Path outputPath = dataDir.resolve("top-credit-cards.json");

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            log.info("Agent turn {}/{}", turn, MAX_TURNS);

            AgentResponse response = provider.nextTurn();
            log.debug("stop_reason={}", response.stopReason());

            if ("end_turn".equals(response.stopReason())) break;
            if ("pause_turn".equals(response.stopReason())) continue;

            if ("tool_use".equals(response.stopReason())) {
                List<Map.Entry<ToolCall, String>> results = new ArrayList<>();
                for (ToolCall tc : response.toolCalls()) {
                    if ("save_top_cards".equals(tc.name())) {
                        results.add(Map.entry(tc, saveTopCards(tc, dataDir)));
                    }
                }
                if (!results.isEmpty()) {
                    provider.submitToolResults(results);
                } else {
                    log.warn("tool_use stop but no recognized tool in response; stopping.");
                    break;
                }
            }
        }

        if (!Files.exists(outputPath)) {
            throw new IllegalStateException("Agent finished without writing " + outputPath);
        }
        return outputPath;
    }

    private static String saveTopCards(ToolCall tc, Path dataDir) {
        try {
            String cardsJson = (String) tc.input().get("cards_json");
            if (cardsJson == null) {
                throw new IllegalArgumentException("cards_json missing from tool input");
            }

            List<Map<String, Object>> cards = MAPPER.readValue(
                cardsJson,
                MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

            List<Map<String, Object>> ranked = CardRanker.rankCards(cards);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("generated_at", Instant.now().toString());
            output.put("source", "web_search");
            output.put("total", ranked.size());
            output.put("top_cards", ranked);

            Path outputPath = dataDir.resolve("top-credit-cards.json");
            Files.createDirectories(dataDir);
            Files.writeString(outputPath, MAPPER.writeValueAsString(output), StandardCharsets.UTF_8);
            log.info("Saved {} ranked cards → {}", ranked.size(), outputPath);
            return "Saved " + ranked.size() + " ranked cards to " + outputPath;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        Path out = run();
        log.info("Done → {}", out);
    }
}
