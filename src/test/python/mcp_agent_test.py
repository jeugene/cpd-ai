import json
from configparser import ConfigParser

import pytest

import agents.cards_agent as agent_mod
import agents.provider as provider_mod
from agents.provider import AgentResponse, ToolCall
from agents.ranker import rank_cards, score_card

# ── helpers ────────────────────────────────────────────────────────────────────


def _card(name="Test Card", min_apr=20.0, fee=0.0, rewards=15.0, bonus=10.0):
    return {
        "name": name,
        "issuer": "Test Bank",
        "card_type": "Cash Back",
        "apr": {"min": min_apr, "max": min_apr + 6},
        "annual_fee": fee,
        "rewards_rate": "2% cash back",
        "sign_up_bonus": "$200 after $500 spend",
        "key_features": ["No foreign fees"],
        "rewards_score": rewards,
        "bonus_score": bonus,
    }


class MockProvider:
    """Stub provider that replays a fixed sequence of AgentResponse objects."""

    def __init__(self, responses: list[AgentResponse]) -> None:
        self._responses = iter(responses)
        self.turn_count = 0
        self.submitted_results: list = []

    def next_turn(self) -> AgentResponse:
        self.turn_count += 1
        return next(self._responses)

    def submit_tool_results(self, results: list) -> None:
        self.submitted_results.extend(results)


def _make_tool_response(cards: list[dict]) -> AgentResponse:
    tc = ToolCall(id="toolu_test_001", name="save_top_cards", input={"cards_json": json.dumps(cards)})
    return AgentResponse(stop_reason="tool_use", tool_calls=[tc])


def _make_dict_tool_response(cards: list[dict]) -> AgentResponse:
    """Simulate a model wrapping cards in {"cards": [...]} instead of a bare array."""
    tc = ToolCall(id="toolu_test_002", name="save_top_cards", input={"cards_json": json.dumps({"cards": cards})})
    return AgentResponse(stop_reason="tool_use", tool_calls=[tc])


def _make_end_response() -> AgentResponse:
    return AgentResponse(stop_reason="end_turn")


def _cfg_with_provider(name: str) -> ConfigParser:
    cfg = ConfigParser()
    cfg.read_dict({"agents": {"provider": name}})
    return cfg


# ── ranker unit tests ──────────────────────────────────────────────────────────


def test_score_lower_apr_wins():
    assert score_card(_card(min_apr=15)) > score_card(_card(min_apr=25))


def test_score_lower_fee_wins():
    assert score_card(_card(fee=0)) > score_card(_card(fee=500))


def test_score_higher_rewards_wins():
    assert score_card(_card(rewards=25)) > score_card(_card(rewards=0))


def test_score_max_does_not_exceed_100():
    perfect = score_card(_card(min_apr=10, fee=0, rewards=25, bonus=20))
    assert perfect <= 100.0


def test_score_missing_fields_do_not_crash():
    assert score_card({}) == 0.0


def test_rank_assigns_sequential_ranks():
    cards = [_card(f"Card {i}", min_apr=10 + i) for i in range(3)]
    ranked = rank_cards(cards)
    assert [c["rank"] for c in ranked] == [1, 2, 3]


def test_rank_sorted_descending_by_score():
    cards = [_card(min_apr=25), _card(min_apr=10), _card(min_apr=20)]
    ranked = rank_cards(cards)
    scores = [c["score"] for c in ranked]
    assert scores == sorted(scores, reverse=True)


def test_rank_lowest_apr_is_first():
    cards = [_card(f"Card {i}", min_apr=30 - i * 5) for i in range(4)]
    ranked = rank_cards(cards)
    assert ranked[0]["apr"]["min"] == min(c["apr"]["min"] for c in cards)


# ── fixtures ───────────────────────────────────────────────────────────────────


@pytest.fixture()
def sample_cards():
    return [_card(f"Card {i}", min_apr=15.0 + i, fee=float(i * 50), rewards=20.0 - i, bonus=15.0 - i) for i in range(8)]


@pytest.fixture()
def _patched_paths(tmp_path, monkeypatch):
    """Patch DATA_DIR / OUTPUT_PATH only; caller injects any provider."""
    out_path = tmp_path / "top-credit-cards.json"
    monkeypatch.setattr(agent_mod, "DATA_DIR", tmp_path)
    monkeypatch.setattr(agent_mod, "OUTPUT_PATH", out_path)
    return out_path


@pytest.fixture()
def _patched_agent(tmp_path, monkeypatch, sample_cards):
    """Patch DATA_DIR / OUTPUT_PATH and provide a default two-turn mock provider."""
    out_path = tmp_path / "top-credit-cards.json"
    monkeypatch.setattr(agent_mod, "DATA_DIR", tmp_path)
    monkeypatch.setattr(agent_mod, "OUTPUT_PATH", out_path)
    mock_provider = MockProvider([_make_tool_response(sample_cards), _make_end_response()])
    yield out_path, mock_provider


# ── agent integration (mock provider) ─────────────────────────────────────────


def test_agent_creates_output_file(_patched_agent):
    out_path, mock_provider = _patched_agent
    result = agent_mod.run(provider=mock_provider)
    assert result == out_path
    assert out_path.exists()


def test_agent_output_has_expected_keys(_patched_agent, sample_cards):
    out_path, mock_provider = _patched_agent
    agent_mod.run(provider=mock_provider)
    data = json.loads(out_path.read_text())
    assert "generated_at" in data
    assert "top_cards" in data
    assert data["total"] == len(sample_cards)


def test_agent_output_cards_are_ranked(_patched_agent, sample_cards):
    out_path, mock_provider = _patched_agent
    agent_mod.run(provider=mock_provider)
    top_cards = json.loads(out_path.read_text())["top_cards"]
    assert top_cards[0]["rank"] == 1
    assert top_cards[-1]["rank"] == len(sample_cards)
    scores = [c["score"] for c in top_cards]
    assert scores == sorted(scores, reverse=True)


def test_agent_calls_provider_twice(_patched_agent):
    """tool_use + end_turn = exactly two provider turns."""
    _, mock_provider = _patched_agent
    agent_mod.run(provider=mock_provider)
    assert mock_provider.turn_count == 2


def test_agent_raises_if_save_never_called(tmp_path, monkeypatch):
    """Agent that ends without calling save_top_cards must raise RuntimeError."""
    out_path = tmp_path / "top-credit-cards.json"
    monkeypatch.setattr(agent_mod, "DATA_DIR", tmp_path)
    monkeypatch.setattr(agent_mod, "OUTPUT_PATH", out_path)
    mock_provider = MockProvider([_make_end_response()])
    with pytest.raises(RuntimeError, match="without writing"):
        agent_mod.run(provider=mock_provider)


def test_agent_accepts_dict_wrapped_cards(_patched_paths, sample_cards):
    """save_top_cards handles {"cards": [...]} wrapping in addition to bare arrays."""
    out_path = _patched_paths
    mock_provider = MockProvider([_make_dict_tool_response(sample_cards), _make_end_response()])
    agent_mod.run(provider=mock_provider)
    data = json.loads(out_path.read_text())
    assert data["total"] == len(sample_cards)


def test_agent_pause_turn_continues_loop(_patched_paths, sample_cards):
    """pause_turn must re-invoke next_turn without submitting tool results."""
    out_path = _patched_paths
    mock_provider = MockProvider([
        AgentResponse(stop_reason="pause_turn"),
        _make_tool_response(sample_cards),
        _make_end_response(),
    ])
    agent_mod.run(provider=mock_provider)
    assert mock_provider.turn_count == 3
    assert out_path.exists()
    assert len(mock_provider.submitted_results) == 1  # only the save_top_cards result


def test_agent_unrecognized_tool_raises(_patched_paths):
    """tool_use with no recognized tool name stops the loop and raises RuntimeError."""
    unknown_tc = ToolCall(id="toolu_unknown", name="unknown_tool", input={})
    mock_provider = MockProvider([AgentResponse(stop_reason="tool_use", tool_calls=[unknown_tc])])
    with pytest.raises(RuntimeError, match="without writing"):
        agent_mod.run(provider=mock_provider)


def test_agent_max_turns_exhaustion_raises(_patched_paths):
    """Exhausting max_turns without a save must raise RuntimeError."""
    mock_provider = MockProvider([AgentResponse(stop_reason="pause_turn")] * 3)
    with pytest.raises(RuntimeError, match="without writing"):
        agent_mod.run(max_turns=3, provider=mock_provider)


# ── provider factory tests ─────────────────────────────────────────────────────


def test_make_provider_anthropic(monkeypatch):
    class Stub:
        def __init__(self, *a, **kw):
            pass

    monkeypatch.setattr(provider_mod, "AnthropicProvider", Stub)
    result = provider_mod.make_provider(_cfg_with_provider("anthropic"), "s", "k", [])
    assert isinstance(result, Stub)


def test_make_provider_openai(monkeypatch):
    received = {}

    class Stub:
        def __init__(self, cfg, system, kickoff, custom_tools, name):
            received["name"] = name

    monkeypatch.setattr(provider_mod, "OpenAICompatibleProvider", Stub)
    result = provider_mod.make_provider(_cfg_with_provider("openai"), "s", "k", [])
    assert isinstance(result, Stub)
    assert received["name"] == "openai"


def test_make_provider_copilot(monkeypatch):
    received = {}

    class Stub:
        def __init__(self, cfg, system, kickoff, custom_tools, name):
            received["name"] = name

    monkeypatch.setattr(provider_mod, "OpenAICompatibleProvider", Stub)
    result = provider_mod.make_provider(_cfg_with_provider("copilot"), "s", "k", [])
    assert isinstance(result, Stub)
    assert received["name"] == "copilot"


def test_make_provider_bedrock(monkeypatch):
    class Stub:
        def __init__(self, *a, **kw):
            pass

    monkeypatch.setattr(provider_mod, "BedrockProvider", Stub)
    result = provider_mod.make_provider(_cfg_with_provider("bedrock"), "s", "k", [])
    assert isinstance(result, Stub)


def test_make_provider_unknown_raises():
    with pytest.raises(ValueError, match="Unknown provider"):
        provider_mod.make_provider(_cfg_with_provider("groq"), "s", "k", [])
