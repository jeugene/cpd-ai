use serde_json::Value;

/// Composite score. Max 100.
/// APR 0–30 pts, Fee 0–25 pts, Rewards 0–25 pts, Bonus 0–20 pts.
pub fn score_card(card: &Value) -> f64 {
    let mut score = 0.0;

    if let Some(apr) = card.get("apr") {
        let min_apr = if apr.is_object() {
            apr.get("min").and_then(|v| v.as_f64())
        } else {
            apr.as_f64()
        };
        if let Some(min) = min_apr {
            score += f64::max(0.0, 30.0 - f64::max(0.0, min - 10.0));
        }
    }

    if let Some(fee) = card.get("annual_fee").and_then(|v| v.as_f64()) {
        score += f64::max(0.0, 25.0 - fee / 20.0);
    }

    score += f64::min(
        25.0,
        card.get("rewards_score")
            .and_then(|v| v.as_f64())
            .unwrap_or(0.0),
    );
    score += f64::min(
        20.0,
        card.get("bonus_score")
            .and_then(|v| v.as_f64())
            .unwrap_or(0.0),
    );

    (score * 100.0).round() / 100.0
}

/// Rank cards by composite score descending. Adds `score` and `rank` fields.
pub fn rank_cards(cards: Vec<Value>) -> Vec<Value> {
    let mut cards: Vec<Value> = cards
        .into_iter()
        .map(|mut card| {
            let score = score_card(&card);
            if let Value::Object(ref mut map) = card {
                map.insert("score".to_string(), Value::from(score));
            }
            card
        })
        .collect();

    cards.sort_by(|a, b| {
        let sa = a.get("score").and_then(|v| v.as_f64()).unwrap_or(0.0);
        let sb = b.get("score").and_then(|v| v.as_f64()).unwrap_or(0.0);
        sb.partial_cmp(&sa).unwrap_or(std::cmp::Ordering::Equal)
    });

    cards
        .into_iter()
        .enumerate()
        .map(|(i, mut card)| {
            if let Value::Object(ref mut map) = card {
                map.insert("rank".to_string(), Value::from(i + 1));
            }
            card
        })
        .collect()
}
