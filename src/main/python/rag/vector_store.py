"""
Amazon S3 Vectors client wrapper.

put_chunks   — store pre-embedded chunks in a vector bucket.
query_chunks — ANN search returning top-k metadata dicts.

Both accept an optional s3vectors_client kwarg for test injection.
"""

import logging
from typing import Any

from core.aws import s3vectors_api
from core.config import config as _config_fn

log = logging.getLogger(__name__)

_cfg = _config_fn()
_VECTOR_BUCKET: str = _cfg.get("rag", "vector_bucket", fallback="rag-vectors")
_TOP_K: int = int(_cfg.get("rag", "top_k", fallback="5"))


def put_chunks(
    chunks: list[dict[str, Any]],
    *,
    vector_bucket: str = _VECTOR_BUCKET,
    s3vectors_client=None,
) -> int:
    """Store embedded chunks in the S3 Vectors bucket.

    Args:
        chunks: List of dicts, each with keys:
                  key      (str)         — unique identifier
                  vector   (list[float]) — embedding from embed_text()
                  metadata (dict)        — arbitrary metadata (source, text, …)
        vector_bucket: Override the config-driven bucket name.
        s3vectors_client: Optional pre-built boto3 s3vectors client.

    Returns:
        Number of chunks stored.
    """
    if not chunks:
        return 0

    client = s3vectors_client or s3vectors_api()
    vectors = [
        {
            "key": c["key"],
            "data": {"float32": c["vector"]},
            "metadata": c["metadata"],
        }
        for c in chunks
    ]
    client.put_vectors(vectorBucketName=vector_bucket, vectors=vectors)
    log.info("Stored %d vectors in bucket %s", len(chunks), vector_bucket)
    return len(chunks)


def query_chunks(
    query_vector: list[float],
    *,
    top_k: int = _TOP_K,
    vector_bucket: str = _VECTOR_BUCKET,
    s3vectors_client=None,
) -> list[dict[str, Any]]:
    """ANN search against the vector bucket.

    Args:
        query_vector: Embedding of the user's question.
        top_k: Number of nearest neighbours to retrieve.
        vector_bucket: Override the config-driven bucket name.
        s3vectors_client: Optional pre-built boto3 s3vectors client.

    Returns:
        List of metadata dicts for the top-k hits in score-descending order.
    """
    client = s3vectors_client or s3vectors_api()
    response = client.query_vectors(
        vectorBucketName=vector_bucket,
        queryVector={"float32": query_vector},
        topK=top_k,
        returnMetadata=True,
    )
    hits = response.get("vectors", [])
    log.debug("ANN query returned %d hits (top_k=%d)", len(hits), top_k)
    return [h["metadata"] for h in hits]
