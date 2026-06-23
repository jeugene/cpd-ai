"""RAG module: ingestion and query pipelines using Amazon S3 Vectors + Bedrock."""

from .embedder import embed_text
from .ingest import ingest_s3_prefix
from .pipeline import answer_question
from .vector_store import put_chunks, query_chunks

__all__ = ["embed_text", "ingest_s3_prefix", "answer_question", "put_chunks", "query_chunks"]
