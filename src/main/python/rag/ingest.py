"""
S3 text ingestion pipeline.

chunk_text       — pure function: split text into overlapping windows.
ingest_document  — read one S3 object → chunk → embed → store in S3 Vectors.
ingest_s3_prefix — list all objects under a prefix and ingest each one.

All AWS clients are injectable for testing.
"""

import hashlib
import logging
from typing import Any

from core.aws import s3_api
from core.config import config as _config_fn
from rag.embedder import embed_text
from rag.vector_store import put_chunks

log = logging.getLogger(__name__)

_cfg = _config_fn()
_CHUNK_SIZE: int = int(_cfg.get("rag", "chunk_size", fallback="500"))
_CHUNK_OVERLAP: int = int(_cfg.get("rag", "chunk_overlap", fallback="50"))


# ── pure functions ─────────────────────────────────────────────────────────────


def chunk_text(text: str, chunk_size: int = _CHUNK_SIZE, overlap: int = _CHUNK_OVERLAP) -> list[str]:
    """Split text into overlapping fixed-size character windows.

    Args:
        text: Source text.
        chunk_size: Maximum characters per chunk.
        overlap: Characters to repeat from the tail of the previous chunk.

    Returns:
        List of non-empty chunk strings.
    """
    if not text:
        return []
    step = max(1, chunk_size - overlap)
    return [text[i : i + chunk_size] for i in range(0, len(text), step) if text[i : i + chunk_size].strip()]


def _chunk_key(source_key: str, chunk_index: int) -> str:
    """Deterministic vector key: SHA1 of 'source_key#chunk_index'."""
    return hashlib.sha1(f"{source_key}#{chunk_index}".encode()).hexdigest()  # noqa: S324


# ── I/O functions (injectable clients) ────────────────────────────────────────


def read_s3_text(bucket: str, key: str, *, s3_client=None) -> str:
    """Download a UTF-8 text object from S3."""
    client = s3_client or s3_api()
    body = client.get_object(Bucket=bucket, Key=key)["Body"].read()
    return body.decode("utf-8")


def _list_s3_keys(bucket: str, prefix: str, *, s3_client=None) -> list[str]:
    """Return all object keys under a prefix using paginated list_objects_v2."""
    client = s3_client or s3_api()
    paginator = client.get_paginator("list_objects_v2")
    keys = []
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            keys.append(obj["Key"])
    return keys


def ingest_document(
    bucket: str,
    key: str,
    *,
    s3_client=None,
    bedrock_client=None,
    s3vectors_client=None,
    vector_bucket: str | None = None,
    chunk_size: int = _CHUNK_SIZE,
    overlap: int = _CHUNK_OVERLAP,
) -> int:
    """Ingest a single S3 text document: read → chunk → embed → store.

    Args:
        bucket: Source S3 bucket.
        key: Object key of the text document.
        s3_client: Injectable S3 client.
        bedrock_client: Injectable Bedrock runtime client.
        s3vectors_client: Injectable S3 Vectors client.
        vector_bucket: Override vector store bucket name.
        chunk_size: Characters per chunk.
        overlap: Overlap characters between consecutive chunks.

    Returns:
        Number of chunks stored.
    """
    log.info("Ingesting s3://%s/%s", bucket, key)
    text = read_s3_text(bucket, key, s3_client=s3_client)
    chunks_text = chunk_text(text, chunk_size=chunk_size, overlap=overlap)
    log.debug("Split into %d chunks", len(chunks_text))

    chunks: list[dict[str, Any]] = []
    for idx, chunk in enumerate(chunks_text):
        vector = embed_text(chunk, bedrock_client=bedrock_client)
        chunks.append({
            "key": _chunk_key(key, idx),
            "vector": vector,
            "metadata": {
                "source_bucket": bucket,
                "source_key": key,
                "chunk_index": idx,
                "text": chunk,
            },
        })

    put_kwargs: dict[str, Any] = {"s3vectors_client": s3vectors_client}
    if vector_bucket:
        put_kwargs["vector_bucket"] = vector_bucket
    return put_chunks(chunks, **put_kwargs)


def ingest_s3_prefix(
    bucket: str,
    prefix: str,
    *,
    s3_client=None,
    bedrock_client=None,
    s3vectors_client=None,
    vector_bucket: str | None = None,
) -> int:
    """Ingest all text objects under an S3 prefix.

    Returns:
        Total number of chunks stored across all documents.
    """
    keys = _list_s3_keys(bucket, prefix, s3_client=s3_client)
    log.info("Found %d objects under s3://%s/%s", len(keys), bucket, prefix)
    total = 0
    for key in keys:
        try:
            total += ingest_document(
                bucket,
                key,
                s3_client=s3_client,
                bedrock_client=bedrock_client,
                s3vectors_client=s3vectors_client,
                vector_bucket=vector_bucket,
            )
        except Exception:
            log.exception("Failed to ingest s3://%s/%s — skipping", bucket, key)
    return total
