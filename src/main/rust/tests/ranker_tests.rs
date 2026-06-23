use cpd_ai_agents::agents::ranker::{rank_cards, score_card};
use serde_json::{json, Value};

fn card(name: &str, min_apr: f64, fee: f64, rewards: f64, bonus: f64) -> Value {
    json!({
        "name": name,
        "issuer": "Test Bank",
        "card_type": "Cash Back",
        "apr": {"min": min_apr, "max": min_apr + 6.0},
        "annual_fee": fee,
        "rewards_rate": "2% cash back",
        "sign_up_bonus": "$200 after $500 spend",
        "key_features": ["No foreign fees"],
        "rewards_score": rewards,
        "bonus_score": bonus,
    })
}

// ── score ─────────────────────────────────────────────────────────────────────

#[test]
fn score_lower_apr_wins() {
    assert!(score_card(&card("A", 15.0, 0.0, 15.0, 10.0)) > score_card(&card("B", 25.0, 0.0, 15.0, 10.0)));
}

#[test]
fn score_lower_fee_wins() {
    assert!(score_card(&card("A", 20.0, 0.0, 15.0, 10.0)) > score_card(&card("B", 20.0, 500.0, 15.0, 10.0)));
}

#[test]
fn score_higher_rewards_wins() {
    assert!(score_card(&card("A", 20.0, 0.0, 25.0, 10.0)) > score_card(&card("B", 20.0, 0.0, 0.0, 10.0)));
}

#[test]
fn score_max_does_not_exceed_100() {
    let perfect = score_card(&card("A", 10.0, 0.0, 25.0, 20.0));
    assert!((perfect - 100.0).abs() < 0.01);
}

#[test]
fn score_missing_fields_do_not_crash() {
    assert_eq!(0.0, score_card(&json!({})));
}

#[test]
fn score_high_apr_is_zero_contribution() {
    // APR=40 → max(0, 30 - max(0, 40-10)) = 0; fee=0 → 25; rewards/bonus=0
    let s = score_card(&card("A", 40.0, 0.0, 0.0, 0.0));
    assert!((s - 25.0).abs() < 0.01);
}

#[test]
fn score_rewards_capped_at_25() {
    let c = json!({"rewards_score": 999.0, "bonus_score": 0.0});
    assert!(score_card(&c) <= 25.0);
}

#[test]
fn score_bonus_capped_at_20() {
    let c = json!({"rewards_score": 0.0, "bonus_score": 999.0});
    assert!(score_card(&c) <= 20.0);
}

// ── rank ──────────────────────────────────────────────────────────────────────

#[test]
fn rank_assigns_sequential_ranks() {
    let cards: Vec<Value> = (0..3)
        .map(|i| card(&format!("Card {i}"), 10.0 + i as f64, 0.0, 15.0, 10.0))
        .collect();
    let ranked = rank_cards(cards);
    let ranks: Vec<u64> = ranked.iter().map(|c| c["rank"].as_u64().unwrap()).collect();
    assert_eq!(ranks, vec![1, 2, 3]);
}

#[test]
fn rank_sorted_descending_by_score() {
    let cards = vec![
        card("A", 25.0, 0.0, 15.0, 10.0),
        card("B", 10.0, 0.0, 15.0, 10.0),
        card("C", 20.0, 0.0, 15.0, 10.0),
    ];
    let ranked = rank_cards(cards);
    let scores: Vec<f64> = ranked.iter().map(|c| c["score"].as_f64().unwrap()).collect();
    let mut sorted = scores.clone();
    sorted.sort_by(|a, b| b.partial_cmp(a).unwrap());
    assert_eq!(scores, sorted);
}

#[test]
fn rank_lowest_apr_is_first() {
    let cards: Vec<Value> = (0..4)
        .map(|i| card(&format!("Card {i}"), 30.0 - i as f64 * 5.0, 0.0, 15.0, 10.0))
        .collect();
    let min_apr = cards
        .iter()
        .map(|c| c["apr"]["min"].as_f64().unwrap())
        .fold(f64::INFINITY, f64::min);
    let ranked = rank_cards(cards);
    let first_apr = ranked[0]["apr"]["min"].as_f64().unwrap();
    assert!((first_apr - min_apr).abs() < 0.001);
}

#[test]
fn rank_single_card_has_rank_one() {
    let cards = vec![card("Solo", 20.0, 0.0, 15.0, 10.0)];
    assert_eq!(rank_cards(cards)[0]["rank"].as_u64().unwrap(), 1);
}

#[test]
fn rank_adds_score_field() {
    let cards = vec![card("X", 20.0, 100.0, 15.0, 10.0)];
    let ranked = rank_cards(cards);
    assert!(ranked[0].get("score").is_some());
    assert!(ranked[0]["score"].as_f64().unwrap() > 0.0);
}
