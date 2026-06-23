"""
Unit tests for sqs_to_s3.py.

pyiceberg is a Lambda-only dependency and is not installed locally.
Tests that exercise upsert_records mock it at the sys.modules level so the
suite runs without it.
"""

import json
import sys
from unittest.mock import MagicMock, patch

import pyarrow as pa

# ── mock pyiceberg before the module under test imports it ─────────────────────

_pyiceberg_mock = MagicMock()
_pyiceberg_mock.expressions.In = MagicMock(return_value="<In filter>")
sys.modules.setdefault("pyiceberg", _pyiceberg_mock)
sys.modules.setdefault("pyiceberg.catalog", _pyiceberg_mock.catalog)
sys.modules.setdefault("pyiceberg.catalog.glue", _pyiceberg_mock.catalog.glue)
sys.modules.setdefault("pyiceberg.expressions", _pyiceberg_mock.expressions)

import sqs_to_s3 as mod  # noqa: E402
from sqs_to_s3 import handler, parse_s3_records, read_s3_json, upsert_records  # noqa: E402

# ── helpers ────────────────────────────────────────────────────────────────────


def _sqs_event(*s3_pairs: tuple[str, str], msg_id: str = "msg-001") -> dict:
    """Build a minimal SQS ESM event wrapping a direct S3 notification."""
    s3_records = [
        {"s3": {"bucket": {"name": b}, "object": {"key": k}}}
        for b, k in s3_pairs
    ]
    return {"Records": [{"messageId": msg_id, "body": json.dumps({"Records": s3_records})}]}


def _sns_event(bucket: str, key: str, msg_id: str = "msg-sns-001") -> dict:
    """Build an SQS ESM event where the body is SNS-wrapped (S3 → SNS → SQS)."""
    s3_notification = {"Records": [{"s3": {"bucket": {"name": bucket}, "object": {"key": key}}}]}
    sns_body = {"Type": "Notification", "Message": json.dumps(s3_notification)}
    return {"Records": [{"messageId": msg_id, "body": json.dumps(sns_body)}]}


def _mock_s3(body: str) -> MagicMock:
    client = MagicMock()
    client.get_object.return_value = {"Body": MagicMock(read=lambda: body.encode())}
    return client


def _mock_catalog(fields: list[tuple[str, pa.DataType]] | None = None):
    """Return (catalog_mock, table_mock) with a real PyArrow schema."""
    schema = pa.schema([pa.field(n, t) for n, t in (fields or [("card_id", pa.string()), ("amount", pa.float64())])])
    table_mock = MagicMock()
    table_mock.schema.return_value.as_arrow.return_value = schema
    catalog_mock = MagicMock()
    catalog_mock.load_table.return_value = table_mock
    return catalog_mock, table_mock


# ── parse_s3_records ───────────────────────────────────────────────────────────


def test_parse_single_record():
    assert parse_s3_records(_sqs_event(("bucket", "data/cards.json"))) == [("bucket", "data/cards.json")]


def test_parse_multiple_records():
    result = parse_s3_records(_sqs_event(("b1", "k1.json"), ("b2", "k2.json")))
    assert result == [("b1", "k1.json"), ("b2", "k2.json")]


def test_parse_empty_event():
    assert parse_s3_records({}) == []
    assert parse_s3_records({"Records": []}) == []


def test_parse_url_encoded_spaces():
    _, key = parse_s3_records(_sqs_event(("b", "data/my%20file.json")))[0]
    assert key == "data/my file.json"


def test_parse_url_encoded_plus():
    _, key = parse_s3_records(_sqs_event(("b", "data/my+file.json")))[0]
    assert key == "data/my file.json"


def test_parse_multiple_sqs_messages():
    """Two SQS messages each wrapping one S3 record."""
    event = {
        "Records": [
            {"messageId": "m1", "body": json.dumps({"Records": [{"s3": {"bucket": {"name": "b1"}, "object": {"key": "k1.json"}}}]})},
            {"messageId": "m2", "body": json.dumps({"Records": [{"s3": {"bucket": {"name": "b2"}, "object": {"key": "k2.json"}}}]})},
        ]
    }
    assert parse_s3_records(event) == [("b1", "k1.json"), ("b2", "k2.json")]


def test_parse_sns_wrapped_notification():
    """S3 → SNS → SQS: body.Type == 'Notification', actual S3 event in body.Message."""
    result = parse_s3_records(_sns_event("my-bucket", "data/cards.json"))
    assert result == [("my-bucket", "data/cards.json")]


# ── read_s3_json ───────────────────────────────────────────────────────────────


def test_read_returns_list_unchanged():
    cards = [{"card_id": "c1"}, {"card_id": "c2"}]
    result = read_s3_json("bucket", "key.json", s3_client=_mock_s3(json.dumps(cards)))
    assert result == cards


def test_read_wraps_single_object_in_list():
    card = {"card_id": "c1"}
    result = read_s3_json("bucket", "key.json", s3_client=_mock_s3(json.dumps(card)))
    assert result == [card]


def test_read_calls_correct_bucket_and_key():
    s3 = _mock_s3("[]")
    read_s3_json("my-bucket", "path/to/file.json", s3_client=s3)
    s3.get_object.assert_called_once_with(Bucket="my-bucket", Key="path/to/file.json")


def test_read_empty_list():
    result = read_s3_json("b", "k.json", s3_client=_mock_s3("[]"))
    assert result == []


# ── upsert_records ─────────────────────────────────────────────────────────────


def test_upsert_returns_record_count():
    catalog, _ = _mock_catalog()
    assert upsert_records([{"card_id": "c1", "amount": 100.0}], catalog=catalog) == 1


def test_upsert_empty_records_returns_zero_without_touching_table():
    catalog, table_mock = _mock_catalog()
    assert upsert_records([], catalog=catalog) == 0
    table_mock.overwrite.assert_not_called()
    table_mock.append.assert_not_called()


def test_upsert_calls_overwrite_when_merge_key_present():
    catalog, table_mock = _mock_catalog()
    upsert_records([{"card_id": "c1", "amount": 50.0}], catalog=catalog, merge_key="card_id")
    table_mock.overwrite.assert_called_once()
    table_mock.append.assert_not_called()


def test_upsert_calls_append_when_merge_key_absent():
    catalog, table_mock = _mock_catalog()
    upsert_records([{"amount": 50.0}], catalog=catalog, merge_key="card_id")
    table_mock.append.assert_called_once()
    table_mock.overwrite.assert_not_called()


def test_upsert_passes_correct_namespace_and_table():
    catalog, _ = _mock_catalog()
    upsert_records([{"card_id": "c1", "amount": 0.0}], catalog=catalog, namespace="myns", table_name="mytbl")
    catalog.load_table.assert_called_once_with("myns.mytbl")


def test_upsert_deduplicates_merge_keys():
    """Duplicate card_ids in the batch should result in one key, not two."""
    catalog, table_mock = _mock_catalog()
    records = [{"card_id": "c1", "amount": 1.0}, {"card_id": "c1", "amount": 2.0}]
    upsert_records(records, catalog=catalog, merge_key="card_id")
    in_mock = _pyiceberg_mock.expressions.In
    call_args = in_mock.call_args
    assert call_args[0][0] == "card_id"
    assert set(call_args[0][1]) == {"c1"}


# ── handler ────────────────────────────────────────────────────────────────────


def test_handler_returns_empty_batch_failures_on_success():
    event = _sqs_event(("bucket", "cards.json"))
    with patch.object(mod, "read_s3_json", return_value=[{"card_id": "c1"}]), \
         patch.object(mod, "upsert_records", return_value=1):
        result = handler(event)
    assert result == {"batchItemFailures": []}


def test_handler_empty_event_returns_empty_failures():
    assert handler({"Records": []}) == {"batchItemFailures": []}


def test_handler_processes_multiple_s3_files_in_one_message():
    """One SQS message wrapping two S3 refs — both processed, none fail."""
    event = {
        "Records": [{
            "messageId": "m1",
            "body": json.dumps({"Records": [
                {"s3": {"bucket": {"name": "b"}, "object": {"key": "f1.json"}}},
                {"s3": {"bucket": {"name": "b"}, "object": {"key": "f2.json"}}},
            ]}),
        }]
    }
    with patch.object(mod, "read_s3_json", return_value=[{"card_id": "x"}]), \
         patch.object(mod, "upsert_records", return_value=1):
        result = handler(event)
    assert result == {"batchItemFailures": []}


def test_handler_passes_bucket_and_key_to_read():
    event = _sqs_event(("my-bucket", "path/cards.json"))
    with patch.object(mod, "read_s3_json", return_value=[]) as mock_read, \
         patch.object(mod, "upsert_records", return_value=0):
        handler(event)
    mock_read.assert_called_once_with("my-bucket", "path/cards.json")


def test_handler_reports_failed_message_id():
    """When processing a message raises, its messageId appears in batchItemFailures."""
    event = _sqs_event(("bucket", "bad.json"), msg_id="msg-fail-001")
    with patch.object(mod, "read_s3_json", side_effect=RuntimeError("S3 error")):
        result = handler(event)
    assert result == {"batchItemFailures": [{"itemIdentifier": "msg-fail-001"}]}


def test_handler_isolates_failures_per_message():
    """A failure in one SQS message does not prevent processing of other messages."""
    event = {
        "Records": [
            {"messageId": "ok-msg", "body": json.dumps({"Records": [{"s3": {"bucket": {"name": "b"}, "object": {"key": "good.json"}}}]})},
            {"messageId": "bad-msg", "body": json.dumps({"Records": [{"s3": {"bucket": {"name": "b"}, "object": {"key": "bad.json"}}}]})},
        ]
    }

    def _read(_bucket, key, **_):
        if "bad" in key:
            raise RuntimeError("read error")
        return [{"card_id": "c1"}]

    with patch.object(mod, "read_s3_json", side_effect=_read), \
         patch.object(mod, "upsert_records", return_value=1):
        result = handler(event)

    assert result == {"batchItemFailures": [{"itemIdentifier": "bad-msg"}]}


def test_handler_handles_sns_wrapped_notification():
    """S3 → SNS → SQS routing — handler unwraps and processes correctly."""
    event = _sns_event("my-bucket", "data/cards.json", msg_id="sns-msg-001")
    with patch.object(mod, "read_s3_json", return_value=[{"card_id": "c1"}]) as mock_read, \
         patch.object(mod, "upsert_records", return_value=1):
        result = handler(event)
    assert result == {"batchItemFailures": []}
    mock_read.assert_called_once_with("my-bucket", "data/cards.json")
