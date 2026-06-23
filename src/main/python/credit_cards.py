import logging
import re
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.types import (
    BooleanType,
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)

from core.config import config
from core.spark_utils import get_spark_session

cfg = config()

_HERE = Path(__file__).resolve().parent.parent.parent.parent  # project root
logger = logging.getLogger(__name__)


# Exported constants — mirror config.ini [spark] defaults so tests can reference them
CATALOG = "local"
NAMESPACE = "cpd"
TABLE_NAME = "credit_cards"

_IDENTIFIER_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")

CC_SCHEMA = StructType(
    [
        StructField("card_id", StringType(), True),
        StructField("card_number_masked", StringType(), True),
        StructField("cardholder_name", StringType(), True),
        StructField("account_id", StringType(), True),
        StructField("credit_limit", DoubleType(), True),
        StructField("current_balance", DoubleType(), True),
        StructField("available_credit", DoubleType(), True),
        StructField("card_status", StringType(), True),
        StructField("card_type", StringType(), True),
        StructField("issue_date", StringType(), True),
        StructField("expiry_date", StringType(), True),
        StructField("billing_cycle_day", IntegerType(), True),
        StructField("minimum_payment", DoubleType(), True),
        StructField("annual_fee", DoubleType(), True),
        StructField("interest_rate", DoubleType(), True),
        StructField("last_transaction_date", StringType(), True),
        StructField("last_payment_date", StringType(), True),
        StructField("last_payment_amount", DoubleType(), True),
        StructField("reward_points", LongType(), True),
        StructField("is_primary", BooleanType(), True),
        StructField("currency", StringType(), True),
    ]
)


def _data_path() -> str:
    """Resolve source data path from config. Relative paths are anchored to the project root."""
    path = cfg.get("spark", "cards")
    return path if path.startswith("s3://") else str(_HERE / path)


def read_cc_records(spark: SparkSession, path: str) -> DataFrame:
    """Load credit card records from a JSON file (local or S3) into a typed DataFrame."""
    logger.info("Reading credit card records from: %s", path)
    df = spark.read.schema(CC_SCHEMA).option("multiline", "true").json(path)
    logger.info("Loaded %d credit card records", df.count())
    return df


def write_iceberg(
    df: DataFrame,
    namespace: str = NAMESPACE,
    table: str = TABLE_NAME,
    catalog: str = CATALOG,
    merge_key: str = "card_id",
) -> int:
    """Write (or upsert) a DataFrame into an Iceberg table.

    First run creates the table; subsequent runs upsert via MERGE INTO keyed on merge_key.
    Returns the total row count after writing.
    """
    for label, value in (("catalog", catalog), ("namespace", namespace), ("table", table), ("merge_key", merge_key)):
        if not _IDENTIFIER_RE.match(value):
            raise ValueError(f"Invalid identifier {label}={value!r}")

    fqt = f"{catalog}.{namespace}.{table}"
    spark = df.sparkSession

    spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {catalog}.{namespace}")

    if not spark.catalog.tableExists(fqt):
        logger.info("Creating Iceberg table: %s", fqt)
        df.writeTo(fqt).create()
    else:
        logger.info("Upserting into Iceberg table: %s (key=%s)", fqt, merge_key)
        df.createOrReplaceTempView("cc_source")
        spark.sql(f"""
            MERGE INTO {fqt} AS target
            USING cc_source AS source
            ON target.{merge_key} = source.{merge_key}
            WHEN MATCHED THEN UPDATE SET *
            WHEN NOT MATCHED THEN INSERT *
        """)

    written = spark.table(fqt).count()
    logger.info("Wrote %d records to %s", written, fqt)
    return written


if __name__ == "__main__":
    spark = get_spark_session("CreditCardsETL")
    df = read_cc_records(spark, _data_path())
    written = write_iceberg(df)
    logger.info("Done. Wrote %d records.", written)
    spark.stop()
