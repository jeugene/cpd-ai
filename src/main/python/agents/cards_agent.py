"""
Credit card research agent.

Fetches current card offers via web search (when available), ranks them,
and writes data/top-credit-cards.json.

Provider is set via config.ini [agents] provider (default: anthropic).
Supported providers: anthropic, openai, copilot, bedrock.

Usage:
    python src/main/python/agents/cards_agent.py
"""

import json
import logging
import os
from datetime import UTC, datetime
from pathlib import Path

from agents.provider import AgentResponse, make_provider
from agents.ranker import rank_cards
from core.config import config

_HERE = Path(__file__).resolve().parent.parent.parent.parent.parent  # project root
DATA_DIR = _HERE / "data"
OUTPUT_PATH = DATA_DIR / "top-credit-cards.json"

logger = logging.getLogger(__name__)

# Only the client-side tools — each provider adds its own web search tool.
_CUSTOM_TOOLS = [
    {
        "name": "save_top_cards",
        "description": (
            "Persist the researched and ranked credit card data to disk. "
            "Call exactly once after you have gathered data for at least 10 cards."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "cards_json": {
                    "type": "string",
                    "description": (
                        "JSON array of credit card objects. Each must include: "
                        "name (str), issuer (str), card_type (str), "
                        "apr (object with 'min' and 'max' float keys), "
                        "annual_fee (float), rewards_rate (str), "
                        "sign_up_bonus (str), key_features (list[str]), "
                        "rewards_score (float 0-25), bonus_score (float 0-20)."
                    ),
                }
            },
            "required": ["cards_json"],
        },
    },
]

_SYSTEM = """\
You are a credit card research agent. Follow these steps exactly:

1. Research the current best US credit cards using available search tools.
   Cover at least three categories:
   - Best travel rewards credit cards (APR, annual fee, points per dollar)
   - Best cash-back credit cards (APR, annual fee, cash-back rate)
   - Best no-annual-fee credit cards (APR, rewards)
   If a web search tool is available, run at least 3 separate searches.

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
"""

_KICKOFF = (
    "Research and rank the best US credit cards available right now. "
    "Cover travel, cash-back, and no-annual-fee categories, "
    "then save the ranked results."
)


def _save_top_cards(cards_json: str) -> str:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    payload = json.loads(cards_json)
    cards = payload if isinstance(payload, list) else payload.get("cards", [])
    ranked = rank_cards(cards)
    output = {
        "generated_at": datetime.now(UTC).isoformat(),
        "source": "web_search",
        "total": len(ranked),
        "top_cards": ranked,
    }
    OUTPUT_PATH.write_text(json.dumps(output, indent=2), encoding="utf-8")
    logger.info("Saved %d ranked cards → %s", len(ranked), OUTPUT_PATH)
    return f"Saved {len(ranked)} ranked cards to {OUTPUT_PATH}"


def run(max_turns: int = 30, provider=None) -> Path:
    """Run the agent and return the path to the written JSON file."""
    if provider is None:
        provider = make_provider(config(), _SYSTEM, _KICKOFF, _CUSTOM_TOOLS)

    for turn in range(1, max_turns + 1):
        logger.info("Agent turn %d/%d", turn, max_turns)
        response: AgentResponse = provider.next_turn()
        logger.debug("stop_reason=%s", response.stop_reason)

        if response.stop_reason == "end_turn":
            break

        if response.stop_reason == "pause_turn":
            # Server-side tool loop hit its iteration limit; re-send to continue.
            continue

        if response.stop_reason == "tool_use":
            results = []
            for tc in response.tool_calls:
                if tc.name == "save_top_cards":
                    result = _save_top_cards(tc.input["cards_json"])
                    results.append((tc, result))
            if results:
                provider.submit_tool_results(results)
            else:
                logger.warning("tool_use stop but no recognized tool in response; stopping.")
                break

    if not OUTPUT_PATH.exists():
        raise RuntimeError(f"Agent finished without writing {OUTPUT_PATH}")

    return OUTPUT_PATH


if __name__ == "__main__":
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    out = run()
    print(f"Done → {out}")
