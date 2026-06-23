package com.cloudpid.ai.agents;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CardRankerTest {

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, Object> card(String name, double minApr, double fee,
                                             double rewards, double bonus) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("issuer", "Test Bank");
        c.put("card_type", "Cash Back");
        c.put("apr", new LinkedHashMap<>(Map.of("min", minApr, "max", minApr + 6)));
        c.put("annual_fee", fee);
        c.put("rewards_rate", "2% cash back");
        c.put("sign_up_bonus", "$200 after $500 spend");
        c.put("key_features", List.of("No foreign fees"));
        c.put("rewards_score", rewards);
        c.put("bonus_score", bonus);
        return c;
    }

    // ── score ────────────────────────────────────────────────────────────────────

    @Test
    void score_lowerAprWins() {
        assertTrue(CardRanker.scoreCard(card("A", 15, 0, 15, 10)) >
                   CardRanker.scoreCard(card("B", 25, 0, 15, 10)));
    }

    @Test
    void score_lowerFeeWins() {
        assertTrue(CardRanker.scoreCard(card("A", 20, 0, 15, 10)) >
                   CardRanker.scoreCard(card("B", 20, 500, 15, 10)));
    }

    @Test
    void score_higherRewardsWins() {
        assertTrue(CardRanker.scoreCard(card("A", 20, 0, 25, 10)) >
                   CardRanker.scoreCard(card("B", 20, 0, 0, 10)));
    }

    @Test
    void score_maxDoesNotExceed100() {
        double perfect = CardRanker.scoreCard(card("A", 10, 0, 25, 20));
        assertEquals(100.0, perfect, 0.01);
    }

    @Test
    void score_missingFieldsDoNotCrash() {
        assertEquals(0.0, CardRanker.scoreCard(new HashMap<>()));
    }

    @Test
    void score_highAprIsZeroContribution() {
        // APR=40 → max(0, 30 - max(0, 40-10)) = 0; fee=0 → 25; rewards/bonus=0
        assertEquals(25.0, CardRanker.scoreCard(card("A", 40, 0, 0, 0)), 0.01);
    }

    @Test
    void score_rewardsCappedAt25() {
        Map<String, Object> c = new HashMap<>(Map.of("rewards_score", 999.0, "bonus_score", 0.0));
        assertTrue(CardRanker.scoreCard(c) <= 25.0);
    }

    @Test
    void score_bonusCappedAt20() {
        Map<String, Object> c = new HashMap<>(Map.of("rewards_score", 0.0, "bonus_score", 999.0));
        assertTrue(CardRanker.scoreCard(c) <= 20.0);
    }

    // ── rank ─────────────────────────────────────────────────────────────────────

    @Test
    void rank_assignsSequentialRanks() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < 3; i++) cards.add(card("Card " + i, 10 + i, 0, 15, 10));
        List<Map<String, Object>> ranked = CardRanker.rankCards(cards);
        assertEquals(List.of(1, 2, 3), ranked.stream().map(c -> c.get("rank")).toList());
    }

    @Test
    void rank_sortedDescendingByScore() {
        List<Map<String, Object>> cards = new ArrayList<>(List.of(
            card("A", 25, 0, 15, 10),
            card("B", 10, 0, 15, 10),
            card("C", 20, 0, 15, 10)
        ));
        List<Map<String, Object>> ranked = CardRanker.rankCards(cards);
        List<Double> scores = ranked.stream().map(c -> (Double) c.get("score")).toList();
        List<Double> sorted = scores.stream().sorted(Comparator.reverseOrder()).toList();
        assertEquals(sorted, scores);
    }

    @Test
    void rank_lowestAprIsFirst() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) cards.add(card("Card " + i, 30 - i * 5.0, 0, 15, 10));
        List<Map<String, Object>> ranked = CardRanker.rankCards(cards);

        double firstApr = ((Number) ((Map<?, ?>) ranked.get(0).get("apr")).get("min")).doubleValue();
        double minApr = cards.stream()
            .mapToDouble(c -> ((Number) ((Map<?, ?>) c.get("apr")).get("min")).doubleValue())
            .min().orElseThrow();
        assertEquals(minApr, firstApr, 0.001);
    }

    @Test
    void rank_singleCardHasRankOne() {
        List<Map<String, Object>> cards = new ArrayList<>(List.of(card("Solo", 20, 0, 15, 10)));
        assertEquals(1, CardRanker.rankCards(cards).get(0).get("rank"));
    }

    @Test
    void rank_addsScoreField() {
        List<Map<String, Object>> cards = new ArrayList<>(List.of(card("X", 20, 100, 15, 10)));
        Map<String, Object> ranked = CardRanker.rankCards(cards).get(0);
        assertTrue(ranked.containsKey("score"));
        assertTrue((double) ranked.get("score") > 0);
    }
}
