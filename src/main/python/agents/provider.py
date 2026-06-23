"""
LLM provider abstraction for the cards agent.

Configured via config.ini [agents] provider:
  anthropic — Anthropic API + server-side web_search_20260209  (default)
  openai    — OpenAI API + built-in web_search_preview
  copilot   — GitHub Copilot via Azure Models API (OpenAI-compatible)
  bedrock   — AWS Bedrock (AnthropicBedrock) — tool use only, no server-side web search
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


# ── normalised types ───────────────────────────────────────────────────────────


@dataclass
class ToolCall:
    id: str
    name: str
    input: dict[str, Any]


@dataclass
class AgentResponse:
    """Normalised response across all providers."""

    stop_reason: str  # "end_turn" | "pause_turn" | "tool_use"
    tool_calls: list[ToolCall] = field(default_factory=list)


# ── providers ──────────────────────────────────────────────────────────────────


class AnthropicProvider:
    _WEB_SEARCH: dict = {"type": "web_search_20260209", "name": "web_search"}
    _DEFAULT_MODEL = "claude-sonnet-4-6"

    def __init__(self, cfg, system: str, kickoff: str, custom_tools: list[dict]) -> None:
        import anthropic

        self._client = anthropic.Anthropic()
        self._model = cfg.get("agents", "anthropic_model", fallback=self._DEFAULT_MODEL)
        self._system = system
        self._tools = [self._WEB_SEARCH] + custom_tools
        self._messages: list[dict] = [{"role": "user", "content": kickoff}]

    def next_turn(self) -> AgentResponse:
        with self._client.messages.stream(
            model=self._model,
            max_tokens=16000,
            thinking={"type": "adaptive"},
            system=self._system,
            tools=self._tools,
            messages=self._messages,
        ) as stream:
            response = stream.get_final_message()
        self._messages.append({"role": "assistant", "content": response.content})
        tool_calls = [
            ToolCall(id=b.id, name=b.name, input=b.input)
            for b in response.content
            if getattr(b, "type", None) == "tool_use"
        ]
        return AgentResponse(stop_reason=response.stop_reason, tool_calls=tool_calls)

    def submit_tool_results(self, results: list[tuple[ToolCall, str]]) -> None:
        self._messages.append({
            "role": "user",
            "content": [
                {"type": "tool_result", "tool_use_id": tc.id, "content": result}
                for tc, result in results
            ],
        })


class OpenAICompatibleProvider:
    """Handles openai and copilot (GitHub Models / Azure) providers."""

    _WEB_SEARCH: dict = {"type": "web_search_preview"}
    _DEFAULT_MODELS = {"openai": "gpt-4o", "copilot": "gpt-4o"}
    _COPILOT_BASE_URL = "https://models.inference.ai.azure.com"

    def __init__(self, cfg, system: str, kickoff: str, custom_tools: list[dict], name: str) -> None:
        import os

        import openai

        if name == "copilot":
            api_key = os.environ.get("GITHUB_TOKEN") or cfg.get("agents", "copilot_api_key", fallback=None)
            self._client = openai.OpenAI(api_key=api_key, base_url=self._COPILOT_BASE_URL)
        else:
            self._client = openai.OpenAI()

        self._model = cfg.get("agents", f"{name}_model", fallback=self._DEFAULT_MODELS[name])
        fn_tools = [
            {
                "type": "function",
                "function": {
                    "name": t["name"],
                    "description": t.get("description", ""),
                    "parameters": t.get("input_schema", {"type": "object", "properties": {}}),
                },
            }
            for t in custom_tools
        ]
        self._tools = [self._WEB_SEARCH] + fn_tools
        self._messages: list[dict] = [
            {"role": "system", "content": system},
            {"role": "user", "content": kickoff},
        ]

    def next_turn(self) -> AgentResponse:
        response = self._client.chat.completions.create(
            model=self._model,
            tools=self._tools,
            messages=self._messages,
            max_tokens=16000,
        )
        choice = response.choices[0]
        msg = choice.message
        msg_dict: dict[str, Any] = {"role": "assistant", "content": msg.content}
        if msg.tool_calls:
            msg_dict["tool_calls"] = [
                {"id": tc.id, "type": tc.type, "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                for tc in msg.tool_calls
            ]
        self._messages.append(msg_dict)

        tool_calls: list[ToolCall] = []
        if choice.finish_reason == "tool_calls" and msg.tool_calls:
            tool_calls = [
                ToolCall(id=tc.id, name=tc.function.name, input=json.loads(tc.function.arguments))
                for tc in msg.tool_calls
            ]

        stop = "tool_use" if choice.finish_reason == "tool_calls" else "end_turn"
        return AgentResponse(stop_reason=stop, tool_calls=tool_calls)

    def submit_tool_results(self, results: list[tuple[ToolCall, str]]) -> None:
        for tc, result in results:
            self._messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})


class BedrockProvider:
    """AWS Bedrock via AnthropicBedrock. No server-side web search available."""

    _DEFAULT_MODEL = "us.anthropic.claude-opus-4-8-20251101"

    def __init__(self, cfg, system: str, kickoff: str, custom_tools: list[dict]) -> None:
        import anthropic

        region = cfg.get("app", "awsRegion", fallback="us-east-1")
        self._client = anthropic.AnthropicBedrock(aws_region=region)
        self._model = cfg.get("agents", "bedrock_model", fallback=self._DEFAULT_MODEL)
        self._system = system
        self._tools = custom_tools  # web_search_20260209 is not available on Bedrock
        self._messages: list[dict] = [{"role": "user", "content": kickoff}]
        logger.warning("Bedrock provider: web search unavailable — agent will use model knowledge only.")

    def next_turn(self) -> AgentResponse:
        with self._client.messages.stream(
            model=self._model,
            max_tokens=16000,
            thinking={"type": "adaptive"},
            system=self._system,
            tools=self._tools,
            messages=self._messages,
        ) as stream:
            response = stream.get_final_message()
        self._messages.append({"role": "assistant", "content": response.content})
        tool_calls = [
            ToolCall(id=b.id, name=b.name, input=b.input)
            for b in response.content
            if getattr(b, "type", None) == "tool_use"
        ]
        return AgentResponse(stop_reason=response.stop_reason, tool_calls=tool_calls)

    def submit_tool_results(self, results: list[tuple[ToolCall, str]]) -> None:
        self._messages.append({
            "role": "user",
            "content": [
                {"type": "tool_result", "tool_use_id": tc.id, "content": result}
                for tc, result in results
            ],
        })


# ── factory ────────────────────────────────────────────────────────────────────


def make_provider(
    cfg, system: str, kickoff: str, custom_tools: list[dict]
) -> AnthropicProvider | OpenAICompatibleProvider | BedrockProvider:
    name = cfg.get("agents", "provider", fallback="anthropic").lower()
    logger.info("Using LLM provider: %s", name)
    if name == "anthropic":
        return AnthropicProvider(cfg, system, kickoff, custom_tools)
    if name in ("openai", "copilot"):
        return OpenAICompatibleProvider(cfg, system, kickoff, custom_tools, name)
    if name == "bedrock":
        return BedrockProvider(cfg, system, kickoff, custom_tools)
    raise ValueError(f"Unknown provider {name!r}. Valid options: anthropic, openai, copilot, bedrock")
