package com.cloudpid.ai.agents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class CardRanker {

    private CardRanker() {}

    /**
     * Composite score for a credit card. Higher is better. Max 100.
     *
     * APR      0–30 pts  (10% → 30, 40% → 0, linear)
     * Fee      0–25 pts  ($0 → 25, $500 → 0, linear)
     * Rewards  0–25 pts  (caller-supplied rewards_score)
     * Bonus    0–20 pts  (caller-supplied bonus_score)
     */
    public static double scoreCard(Map<String, Object> card) {
        double score = 0.0;

        Object aprObj = card.get("apr");
        if (aprObj instanceof Map<?, ?> aprMap) {
            Object minApr = aprMap.get("min");
            if (minApr != null) {
                double v = toDouble(minApr);
                score += Math.max(0.0, 30.0 - Math.max(0.0, v - 10.0));
            }
        }

        Object fee = card.get("annual_fee");
        if (fee != null) {
            score += Math.max(0.0, 25.0 - toDouble(fee) / 20.0);
        }

        score += Math.min(25.0, toDouble(card.getOrDefault("rewards_score", 0)));
        score += Math.min(20.0, toDouble(card.getOrDefault("bonus_score", 0)));

        return Math.round(score * 100.0) / 100.0;
    }

    /** Rank cards by composite score descending. Adds "score" and "rank" keys in place. */
    public static List<Map<String, Object>> rankCards(List<Map<String, Object>> cards) {
        for (Map<String, Object> card : cards) {
            card.put("score", scoreCard(card));
        }
        List<Map<String, Object>> ranked = new ArrayList<>(cards);
        ranked.sort(Comparator.comparingDouble(c -> -(double) c.get("score")));
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).put("rank", i + 1);
        }
        return ranked;
    }

    private static double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }
}
