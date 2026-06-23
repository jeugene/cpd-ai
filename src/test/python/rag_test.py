"""
Unit tests for src/main/python/rag/.

boto3 is in the dev dependency group — no sys.modules mocking needed.
All Bedrock and S3 Vectors calls use injectable clients; no AWS credentials required.
"""

import json
from typing import Any
from unittest.mock import MagicMock

import rag.embedder as embedder_mod
import rag.pipeline as pipeline_mod
import rag.vector_store as vector_store_mod
from rag.embedder import embed_text
from rag.ingest import (
    _chunk_key,
    _list_s3_keys,
    chunk_text,
    ingest_document,
    ingest_s3_prefix,
    read_s3_text,
)
from rag.pipeline import answer_question, build_prompt, generate_answer
from rag.vector_store import put_chunks, query_chunks

# ── helpers ────────────────────────────────────────────────────────────────────


def _mock_bedrock_embed(embedding: list[float] | None = None) -> MagicMock:
    """Bedrock client whose invoke_model returns a Titan embedding response."""
    vec = embedding or [0.1] * 1024
    body_bytes = json.dumps({"embedding": vec}).encode()
    client = MagicMock()
    client.invoke_model.return_value = {"body": MagicMock(read=lambda: body_bytes)}
    return client


def _mock_bedrock_generate(answer_text: str = "Test answer.") -> MagicMock:
    """Bedrock client whose invoke_model returns a Claude generation response."""
    body_bytes = json.dumps({"content": [{"type": "text", "text": answer_text}]}).encode()
    client = MagicMock()
    client.invoke_model.return_value = {"body": MagicMock(read=lambda: body_bytes)}
    return client


def _mock_s3(text_content: str = "Hello world") -> MagicMock:
    """S3 client whose get_object returns the given text content."""
    client = MagicMock()
    client.get_object.return_value = {"Body": MagicMock(read=lambda: text_content.encode("utf-8"))}
    return client


def _mock_s3_list(keys: list[str], text_content: str = "chunk text here") -> MagicMock:
    """S3 client whose paginator returns the given keys; get_object returns text_content."""
    page = {"Contents": [{"Key": k} for k in keys]}
    paginator = MagicMock()
    paginator.paginate.return_value = [page]
    client = MagicMock()
    client.get_paginator.return_value = paginator
    client.get_object.return_value = {"Body": MagicMock(read=lambda: text_content.encode("utf-8"))}
    return client


def _mock_s3vectors(hits: list[dict[str, Any]] | None = None) -> MagicMock:
    """S3 Vectors client stub."""
    client = MagicMock()
    client.query_vectors.return_value = {"vectors": hits or []}
    return client


def _sample_chunk(text: str = "Sample text", idx: int = 0) -> dict[str, Any]:
    return {
        "key": f"chunk-{idx:04d}",
        "vector": [0.1] * 1024,
        "metadata": {"source_bucket": "my-bucket", "source_key": "doc.txt", "chunk_index": idx, "text": text},
    }


# ── embedder ───────────────────────────────────────────────────────────────────


def test_embed_text_returns_list_of_floats():
    result = embed_text("hello banking world", bedrock_client=_mock_bedrock_embed([0.5] * 1024))
    assert isinstance(result, list)
    assert len(result) == 1024
    assert result[0] == 0.5


def test_embed_text_calls_correct_model():
    client = _mock_bedrock_embed()
    embed_text("test", bedrock_client=client)
    assert client.invoke_model.call_args[1]["modelId"] == "amazon.titan-embed-text-v2:0"


def test_embed_text_sends_correct_body():
    client = _mock_bedrock_embed()
    embed_text("my text", bedrock_client=client)
    body = json.loads(client.invoke_model.call_args[1]["body"])
    assert body["inputText"] == "my text"
    assert body["dimensions"] == 1024


def test_embed_text_uses_factory_when_no_client(monkeypatch):
    factory_client = _mock_bedrock_embed()
    monkeypatch.setattr(embedder_mod, "bedrock_runtime_api", lambda: factory_client)
    result = embed_text("test text")
    assert len(result) == 1024


# ── vector_store: put_chunks ───────────────────────────────────────────────────


def test_put_chunks_returns_count():
    client = _mock_s3vectors()
    assert put_chunks([_sample_chunk("a", 0), _sample_chunk("b", 1)], s3vectors_client=client, vector_bucket="vb") == 2


def test_put_chunks_empty_returns_zero_without_call():
    client = _mock_s3vectors()
    assert put_chunks([], s3vectors_client=client) == 0
    client.put_vectors.assert_not_called()


def test_put_chunks_formats_payload_correctly():
    client = _mock_s3vectors()
    chunk = _sample_chunk("hello", 3)
    put_chunks([chunk], s3vectors_client=client, vector_bucket="test-bucket")
    call_kwargs = client.put_vectors.call_args[1]
    assert call_kwargs["vectorBucketName"] == "test-bucket"
    vectors = call_kwargs["vectors"]
    assert len(vectors) == 1
    assert vectors[0]["key"] == chunk["key"]
    assert vectors[0]["data"] == {"float32": chunk["vector"]}
    assert vectors[0]["metadata"] == chunk["metadata"]


def test_put_chunks_uses_factory_when_no_client(monkeypatch):
    factory_client = _mock_s3vectors()
    monkeypatch.setattr(vector_store_mod, "s3vectors_api", lambda: factory_client)
    put_chunks([_sample_chunk()], vector_bucket="bucket")
    factory_client.put_vectors.assert_called_once()


# ── vector_store: query_chunks ─────────────────────────────────────────────────


def test_query_chunks_returns_metadata_list():
    meta = {"source_key": "doc.txt", "chunk_index": 0, "text": "Banking is great"}
    client = _mock_s3vectors(hits=[{"key": "abc", "score": 0.95, "metadata": meta}])
    result = query_chunks([0.1] * 1024, top_k=1, s3vectors_client=client, vector_bucket="vb")
    assert result == [meta]


def test_query_chunks_empty_response_returns_empty_list():
    client = _mock_s3vectors(hits=[])
    assert query_chunks([0.1] * 1024, top_k=5, s3vectors_client=client, vector_bucket="vb") == []


def test_query_chunks_sends_correct_payload():
    client = _mock_s3vectors()
    vec = [0.2] * 1024
    query_chunks(vec, top_k=3, s3vectors_client=client, vector_bucket="my-vec-bucket")
    call_kwargs = client.query_vectors.call_args[1]
    assert call_kwargs["vectorBucketName"] == "my-vec-bucket"
    assert call_kwargs["queryVector"] == {"float32": vec}
    assert call_kwargs["topK"] == 3
    assert call_kwargs["returnMetadata"] is True


def test_query_chunks_uses_factory_when_no_client(monkeypatch):
    factory_client = _mock_s3vectors(hits=[])
    monkeypatch.setattr(vector_store_mod, "s3vectors_api", lambda: factory_client)
    query_chunks([0.1] * 1024, vector_bucket="bucket")
    factory_client.query_vectors.assert_called_once()


# ── ingest: chunk_text (pure function) ────────────────────────────────────────


def test_chunk_text_basic_split():
    chunks = chunk_text("a" * 1000, chunk_size=100, overlap=0)
    assert all(len(c) == 100 for c in chunks)
    assert len(chunks) == 10


def test_chunk_text_overlap():
    chunks = chunk_text("abcdefghij", chunk_size=5, overlap=2)
    # step = 3; starts at 0, 3, 6 → "abcde", "defgh", "ghij"
    assert chunks[0] == "abcde"
    assert chunks[1] == "defgh"


def test_chunk_text_empty_returns_empty():
    assert chunk_text("", chunk_size=100, overlap=10) == []


def test_chunk_text_shorter_than_chunk_size():
    assert chunk_text("short text", chunk_size=500, overlap=50) == ["short text"]


def test_chunk_text_strips_whitespace_only_windows():
    text = "hello" + " " * 500 + "world"
    chunks = chunk_text(text, chunk_size=50, overlap=0)
    assert all(c.strip() for c in chunks)


def test_chunk_key_is_deterministic():
    assert _chunk_key("docs/file.txt", 0) == _chunk_key("docs/file.txt", 0)


def test_chunk_key_differs_by_index_and_source():
    assert _chunk_key("doc.txt", 0) != _chunk_key("doc.txt", 1)
    assert _chunk_key("a.txt", 0) != _chunk_key("b.txt", 0)


# ── ingest: read_s3_text ───────────────────────────────────────────────────────


def test_read_s3_text_returns_string():
    assert read_s3_text("bucket", "doc.txt", s3_client=_mock_s3("hello world")) == "hello world"


def test_read_s3_text_calls_correct_args():
    client = _mock_s3("content")
    read_s3_text("my-bucket", "path/doc.txt", s3_client=client)
    client.get_object.assert_called_once_with(Bucket="my-bucket", Key="path/doc.txt")


# ── ingest: _list_s3_keys ──────────────────────────────────────────────────────


def test_list_s3_keys_returns_keys():
    client = _mock_s3_list(["docs/a.txt", "docs/b.txt"])
    assert _list_s3_keys("bucket", "docs/", s3_client=client) == ["docs/a.txt", "docs/b.txt"]


def test_list_s3_keys_empty_prefix():
    assert _list_s3_keys("bucket", "", s3_client=_mock_s3_list([])) == []


def test_list_s3_keys_calls_paginator():
    client = _mock_s3_list(["k.txt"])
    _list_s3_keys("bucket", "prefix/", s3_client=client)
    client.get_paginator.assert_called_once_with("list_objects_v2")
    client.get_paginator.return_value.paginate.assert_called_once_with(Bucket="bucket", Prefix="prefix/")


# ── ingest: ingest_document ────────────────────────────────────────────────────


def test_ingest_document_returns_chunk_count():
    count = ingest_document(
        "bucket", "doc.txt",
        s3_client=_mock_s3("word " * 200),
        bedrock_client=_mock_bedrock_embed(),
        s3vectors_client=_mock_s3vectors(),
        vector_bucket="vb",
    )
    assert count >= 1


def test_ingest_document_calls_embed_per_chunk():
    text = "a" * 1200
    bedrock = _mock_bedrock_embed()
    ingest_document(
        "bucket", "doc.txt",
        s3_client=_mock_s3(text),
        bedrock_client=bedrock,
        s3vectors_client=_mock_s3vectors(),
        chunk_size=500, overlap=50, vector_bucket="vb",
    )
    expected = len(chunk_text(text, chunk_size=500, overlap=50))
    assert bedrock.invoke_model.call_count == expected


def test_ingest_document_stores_metadata_with_text():
    s3v = _mock_s3vectors()
    ingest_document(
        "my-bucket", "reports/q1.txt",
        s3_client=_mock_s3("Banking document content"),
        bedrock_client=_mock_bedrock_embed(),
        s3vectors_client=s3v,
        vector_bucket="vb",
    )
    meta = s3v.put_vectors.call_args[1]["vectors"][0]["metadata"]
    assert meta["source_bucket"] == "my-bucket"
    assert meta["source_key"] == "reports/q1.txt"
    assert meta["chunk_index"] == 0
    assert "text" in meta


# ── ingest: ingest_s3_prefix ──────────────────────────────────────────────────


def test_ingest_s3_prefix_total_chunks():
    total = ingest_s3_prefix(
        "bucket", "docs/",
        s3_client=_mock_s3_list(["docs/a.txt", "docs/b.txt"], text_content="short text"),
        bedrock_client=_mock_bedrock_embed(),
        s3vectors_client=_mock_s3vectors(),
        vector_bucket="vb",
    )
    assert total >= 2


def test_ingest_s3_prefix_skips_failed_documents():
    """Failure on one document does not prevent the others from being processed."""
    s3 = _mock_s3_list(["good.txt", "bad.txt"])
    call_count = 0

    def _get_object(Bucket, Key):  # noqa: N803
        nonlocal call_count
        call_count += 1
        if "bad" in Key:
            raise RuntimeError("S3 read error")
        return {"Body": MagicMock(read=lambda: b"content")}

    s3.get_object.side_effect = _get_object
    total = ingest_s3_prefix(
        "bucket", "",
        s3_client=s3,
        bedrock_client=_mock_bedrock_embed(),
        s3vectors_client=_mock_s3vectors(),
        vector_bucket="vb",
    )
    assert total >= 1
    assert call_count == 2


# ── pipeline: build_prompt (pure function) ────────────────────────────────────


def test_build_prompt_contains_question():
    assert "What is APR?" in build_prompt("What is APR?", [])["messages"][0]["content"]


def test_build_prompt_contains_context_text():
    chunks = [{"source_key": "doc.txt", "chunk_index": 0, "text": "APR stands for Annual Percentage Rate"}]
    assert "APR stands for Annual Percentage Rate" in build_prompt("What is APR?", chunks)["messages"][0]["content"]


def test_build_prompt_has_anthropic_version():
    assert build_prompt("q?", [])["anthropic_version"] == "bedrock-2023-05-31"


def test_build_prompt_includes_source_attribution():
    chunks = [{"source_key": "reports/q1.txt", "chunk_index": 2, "text": "content"}]
    content = build_prompt("question", chunks)["messages"][0]["content"]
    assert "reports/q1.txt" in content
    assert "chunk 2" in content


def test_build_prompt_empty_context_still_valid():
    prompt = build_prompt("any question", [])
    assert prompt["messages"][0]["role"] == "user"


def test_build_prompt_multiple_chunks_separated_by_divider():
    chunks = [
        {"source_key": "a.txt", "chunk_index": 0, "text": "First chunk"},
        {"source_key": "b.txt", "chunk_index": 0, "text": "Second chunk"},
    ]
    content = build_prompt("q?", chunks)["messages"][0]["content"]
    assert "First chunk" in content
    assert "Second chunk" in content
    assert "---" in content


# ── pipeline: generate_answer ──────────────────────────────────────────────────


def test_generate_answer_returns_text():
    result = generate_answer(build_prompt("question", []), bedrock_client=_mock_bedrock_generate("The answer is 42."))
    assert result == "The answer is 42."


def test_generate_answer_calls_correct_model():
    client = _mock_bedrock_generate()
    generate_answer(build_prompt("q", []), bedrock_client=client)
    assert client.invoke_model.call_args[1]["modelId"] == "us.anthropic.claude-opus-4-8-20251101"


def test_generate_answer_sends_correct_content_type():
    client = _mock_bedrock_generate()
    generate_answer(build_prompt("q", []), bedrock_client=client)
    call_kwargs = client.invoke_model.call_args[1]
    assert call_kwargs["contentType"] == "application/json"
    assert call_kwargs["accept"] == "application/json"


def test_generate_answer_uses_factory_when_no_client(monkeypatch):
    factory_client = _mock_bedrock_generate("answer")
    monkeypatch.setattr(pipeline_mod, "bedrock_runtime_api", lambda: factory_client)
    assert generate_answer(build_prompt("q", [])) == "answer"


# ── pipeline: answer_question (end-to-end, all AWS mocked) ───────────────────


def test_answer_question_end_to_end():
    """Full pipeline: embed → retrieve → generate. One bedrock client, two calls."""
    embed_vec = [0.3] * 1024
    meta = {"source_key": "policy.txt", "chunk_index": 0, "text": "Banking policy content"}
    bedrock = MagicMock()
    embed_body = json.dumps({"embedding": embed_vec}).encode()
    gen_body = json.dumps({"content": [{"type": "text", "text": "Here is the answer."}]}).encode()
    bedrock.invoke_model.side_effect = [
        {"body": MagicMock(read=lambda: embed_body)},
        {"body": MagicMock(read=lambda: gen_body)},
    ]
    s3v = _mock_s3vectors(hits=[{"key": "abc", "score": 0.9, "metadata": meta}])

    result = answer_question("What is banking policy?", bedrock_client=bedrock, s3vectors_client=s3v, vector_bucket="vb")
    assert result == "Here is the answer."
    assert bedrock.invoke_model.call_count == 2


def test_answer_question_no_context_still_generates():
    """When ANN returns no hits, Claude still generates an answer."""
    bedrock = MagicMock()
    embed_body = json.dumps({"embedding": [0.1] * 1024}).encode()
    gen_body = json.dumps({"content": [{"type": "text", "text": "I don't have enough context."}]}).encode()
    bedrock.invoke_model.side_effect = [
        {"body": MagicMock(read=lambda: embed_body)},
        {"body": MagicMock(read=lambda: gen_body)},
    ]
    result = answer_question("Unknown question?", bedrock_client=bedrock, s3vectors_client=_mock_s3vectors(), vector_bucket="vb")
    assert "context" in result.lower()


def test_answer_question_passes_top_k_to_query():
    bedrock = MagicMock()
    embed_body = json.dumps({"embedding": [0.1] * 1024}).encode()
    gen_body = json.dumps({"content": [{"type": "text", "text": "ok"}]}).encode()
    bedrock.invoke_model.side_effect = [
        {"body": MagicMock(read=lambda: embed_body)},
        {"body": MagicMock(read=lambda: gen_body)},
    ]
    s3v = _mock_s3vectors()
    answer_question("q?", top_k=7, bedrock_client=bedrock, s3vectors_client=s3v, vector_bucket="vb")
    assert s3v.query_vectors.call_args[1]["topK"] == 7
