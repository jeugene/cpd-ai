"""
RAG query pipeline.

build_prompt    — pure function: assemble the Bedrock Claude messages payload.
generate_answer — call Bedrock Claude with a pre-built prompt.
answer_question — end-to-end: embed question → ANN search → generate answer.

All AWS clients are injectable for testing.
"""

import json
import logging

from core.aws import bedrock_runtime_api
from core.config import config as _config_fn
from rag.embedder import embed_text
from rag.vector_store import query_chunks

log = logging.getLogger(__name__)

_cfg = _config_fn()
_GEN_MODEL: str = _cfg.get("rag", "gen_model", fallback="us.anthropic.claude-opus-4-8-20251101")
_TOP_K: int = int(_cfg.get("rag", "top_k", fallback="5"))

_SYSTEM_PROMPT = (
    "You are a helpful assistant for a banking data platform. "
    "Answer the user's question using only the provided context. "
    "If the context does not contain enough information, say so clearly. "
    "Do not fabricate facts."
)


# ── pure functions ─────────────────────────────────────────────────────────────


def build_prompt(question: str, context_chunks: list[dict]) -> dict:
    """Assemble the Bedrock invoke_model body for Claude.

    Args:
        question: The user's question string.
        context_chunks: List of metadata dicts returned by query_chunks().
                        Each must contain a 'text' key.

    Returns:
        Dict ready to be JSON-serialised and passed as body to invoke_model.
    """
    context_text = "\n\n---\n\n".join(
        f"[Source: {c.get('source_key', 'unknown')} chunk {c.get('chunk_index', '?')}]\n{c.get('text', '')}"
        for c in context_chunks
    )
    return {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": 2048,
        "system": _SYSTEM_PROMPT,
        "messages": [
            {
                "role": "user",
                "content": f"Context:\n{context_text}\n\nQuestion: {question}",
            }
        ],
    }


# ── I/O functions (injectable clients) ────────────────────────────────────────


def generate_answer(prompt_body: dict, *, bedrock_client=None) -> str:
    """Send a pre-built prompt to Bedrock Claude and return the text response.

    Args:
        prompt_body: Output of build_prompt().
        bedrock_client: Injectable Bedrock runtime client.

    Returns:
        The model's text answer as a plain string.
    """
    client = bedrock_client or bedrock_runtime_api()
    response = client.invoke_model(
        modelId=_GEN_MODEL,
        contentType="application/json",
        accept="application/json",
        body=json.dumps(prompt_body),
    )
    result = json.loads(response["body"].read())
    return result["content"][0]["text"]


def answer_question(
    question: str,
    *,
    top_k: int = _TOP_K,
    bedrock_client=None,
    s3vectors_client=None,
    vector_bucket: str | None = None,
) -> str:
    """End-to-end RAG query: embed question → retrieve context → generate answer.

    Args:
        question: The user's natural language question.
        top_k: Number of chunks to retrieve.
        bedrock_client: Injectable Bedrock runtime client (embed + generate).
        s3vectors_client: Injectable S3 Vectors client.
        vector_bucket: Override vector store bucket name.

    Returns:
        Generated answer string from Claude.
    """
    log.info("RAG query: %r (top_k=%d)", question, top_k)

    query_vector = embed_text(question, bedrock_client=bedrock_client)

    query_kwargs: dict = {"top_k": top_k, "s3vectors_client": s3vectors_client}
    if vector_bucket:
        query_kwargs["vector_bucket"] = vector_bucket
    context_chunks = query_chunks(query_vector, **query_kwargs)

    log.debug("Retrieved %d context chunks", len(context_chunks))
    prompt_body = build_prompt(question, context_chunks)
    return generate_answer(prompt_body, bedrock_client=bedrock_client)
