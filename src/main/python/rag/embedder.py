"""
Bedrock Titan Embeddings v2 wrapper.

embed_text — convert a text string to a dense float vector via Bedrock.

Accepts an optional bedrock_client kwarg so tests can inject a mock
without AWS credentials — identical pattern to sqs_to_s3.read_s3_json.
"""

import json
import logging

from core.aws import bedrock_runtime_api
from core.config import config as _config_fn

log = logging.getLogger(__name__)

_cfg = _config_fn()
_EMBED_MODEL: str = _cfg.get("rag", "embed_model", fallback="amazon.titan-embed-text-v2:0")
_EMBED_DIMS: int = int(_cfg.get("rag", "embed_dims", fallback="1024"))


def embed_text(text: str, *, bedrock_client=None) -> list[float]:
    """Embed a single text string using Titan Embeddings v2.

    Args:
        text: The text to embed. Titan v2 supports up to 8192 tokens.
        bedrock_client: Optional pre-built boto3 bedrock-runtime client.

    Returns:
        List of floats of length _EMBED_DIMS (default 1024).
    """
    client = bedrock_client or bedrock_runtime_api()
    body = json.dumps({"inputText": text, "dimensions": _EMBED_DIMS})
    response = client.invoke_model(
        modelId=_EMBED_MODEL,
        contentType="application/json",
        accept="application/json",
        body=body,
    )
    result = json.loads(response["body"].read())
    log.debug("Embedded %d chars → %d-dim vector", len(text), len(result["embedding"]))
    return result["embedding"]
