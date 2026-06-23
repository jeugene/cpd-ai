from typing import Any


def score_card(card: dict[str, Any]) -> float:
    """
    Composite score for a credit card. Higher is better. Max 100.

    Breakdown:
      APR      0–30 pts  (10% → 30, 40% → 0, linear)
      Fee      0–25 pts  ($0 → 25, $500 → 0, linear)
      Rewards  0–25 pts  (caller-supplied rewards_score)
      Bonus    0–20 pts  (caller-supplied bonus_score)
    """
    score = 0.0

    apr = card.get("apr", {})
    min_apr = apr.get("min") if isinstance(apr, dict) else apr
    if min_apr is not None:
        score += max(0.0, 30.0 - max(0.0, float(min_apr) - 10.0))

    fee = card.get("annual_fee")
    if fee is not None:
        score += max(0.0, 25.0 - float(fee) / 20.0)

    score += min(25.0, float(card.get("rewards_score") or 0))
    score += min(20.0, float(card.get("bonus_score") or 0))

    return round(score, 2)


def rank_cards(cards: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Rank cards by composite score descending. Adds 'score' and 'rank' keys in place."""
    for card in cards:
        card["score"] = score_card(card)
    ranked = sorted(cards, key=lambda c: c["score"], reverse=True)
    for i, card in enumerate(ranked, start=1):
        card["rank"] = i
    return ranked
