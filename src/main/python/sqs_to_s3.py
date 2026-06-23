"""
Lambda handler: SQS event source mapping → S3 notification → S3 table upsert.

Flow:
  S3 file upload
    → S3 event notification (direct or via SNS)
      → SQS queue (event source mapping triggers this Lambda)
        → read data file from S3
          → upsert to Iceberg S3 table via Glue catalog

Each SQS message is processed independently. Failures are reported via
batchItemFailures so SQS retries only the failed messages, not the whole batch.
Enable ReportBatchItemFailures on the event source mapping to activate this.

Configuration from src/main/python/core/config.ini [spark]:
    namespace   Iceberg database
    credit_cards        Iceberg cards table name
    credit_transactions Iceberg transactions table name
    merge_key   Upsert key field

AWS clients and region are provided by core/aws.py.
"""

import json
import logging
import os
from urllib.parse import unquote_plus

from core.aws import s3_api
from core.config import config as _config_fn

logger = logging.getLogger(__name__)
logger.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())

_cfg = _config_fn()
NAMESPACE: str = _cfg.get("spark", "namespace", fallback="cpd")
TABLE_NAME: str = _cfg.get("spark", "credit_cards", fallback="credit_cards")
MERGE_KEY: str = _cfg.get("spark", "merge_key", fallback="card_id")

_catalog = None


def _get_catalog():
    global _catalog
    if _catalog is None:
        from pyiceberg.catalog.glue import GlueCatalog  # lazy — Glue runtime only

        _catalog = GlueCatalog("glue", **{"region_name": _cfg["app"]["awsRegion"]})
    return _catalog


# ── pure functions (no I/O) ────────────────────────────────────────────────────


def _s3_refs_from_body(body: dict) -> list[tuple[str, str]]:
    """Extract (bucket, key) from one parsed SQS message body.

    Handles two routing patterns:
      Direct:  S3 → SQS   — body has a top-level 'Records' list
      Via SNS: S3 → SNS → SQS — body has Type='Notification', S3 event in 'Message'
    """
    if body.get("Type") == "Notification":
        body = json.loads(body["Message"])
    return [
        (rec["s3"]["bucket"]["name"], unquote_plus(rec["s3"]["object"]["key"]))
        for rec in body.get("Records", [])
    ]


def parse_s3_records(event: dict) -> list[tuple[str, str]]:
    """Extract all (bucket, key) pairs from an SQS Lambda event."""
    result = []
    for sqs_record in event.get("Records", []):
        result.extend(_s3_refs_from_body(json.loads(sqs_record["body"])))
    return result


# ── I/O functions (injectable clients for testing) ────────────────────────────


def read_s3_json(bucket: str, key: str, s3_client=None) -> list[dict]:
    """Download and parse a JSON file from S3. Returns a list of records."""
    client = s3_client or s3_api()
    body = client.get_object(Bucket=bucket, Key=key)["Body"].read()
    payload = json.loads(body)
    return payload if isinstance(payload, list) else [payload]


def upsert_records(
    records: list[dict],
    catalog=None,
    namespace: str = NAMESPACE,
    table_name: str = TABLE_NAME,
    merge_key: str = MERGE_KEY,
) -> int:
    """Upsert records into an Iceberg S3 table keyed on merge_key."""
    if not records:
        return 0

    import pyarrow as pa
    from pyiceberg.expressions import In

    cat = catalog or _get_catalog()
    table = cat.load_table(f"{namespace}.{table_name}")
    arrow_df = pa.Table.from_pylist(records, schema=table.schema().as_arrow())

    keys = list({r[merge_key] for r in records if merge_key in r})
    if keys:
        table.overwrite(arrow_df, overwrite_filter=In(merge_key, keys))
    else:
        table.append(arrow_df)

    return len(records)


# ── handler ────────────────────────────────────────────────────────────────────


def handler(event: dict, context=None) -> dict:
    """SQS event source mapping handler.

    Processes each SQS message independently. Failed message IDs are returned
    in batchItemFailures so SQS retries only those messages.
    """
    failures = []
    for sqs_record in event.get("Records", []):
        message_id = sqs_record["messageId"]
        try:
            for bucket, key in _s3_refs_from_body(json.loads(sqs_record["body"])):
                logger.info("Processing s3://%s/%s", bucket, key)
                records = read_s3_json(bucket, key)
                count = upsert_records(records)
                logger.info("Upserted %d records from s3://%s/%s", count, bucket, key)
        except Exception:
            logger.exception("Failed to process message %s", message_id)
            failures.append({"itemIdentifier": message_id})

    return {"batchItemFailures": failures}
