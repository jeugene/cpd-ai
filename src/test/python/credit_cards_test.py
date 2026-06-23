from pathlib import Path

import pytest
from credit_cards import (
    CATALOG,
    NAMESPACE,
    TABLE_NAME,
    read_cc_records,
    write_iceberg,
)

from core.spark_utils import WAREHOUSE_PATH, get_spark_session

DATA_PATH = str(Path(__file__).parent.parent.parent.parent / "tests" / "data" / "credit_cards.json")
VALID_STATUSES = {"ACTIVE", "INACTIVE", "BLOCKED", "EXPIRED"}


@pytest.fixture(scope="session")
def spark():
    if not WAREHOUSE_PATH.exists():
        WAREHOUSE_PATH.mkdir(parents=True, exist_ok=True)
    session = get_spark_session("TestCreditCards")
    yield session
    session.stop()


def test_read_returns_dataframe(spark):
    df = read_cc_records(spark, DATA_PATH)
    assert df is not None
    assert df.count() >= 5


def test_read_schema_columns(spark):
    df = read_cc_records(spark, DATA_PATH)
    assert {
        "card_id",
        "cardholder_name",
        "credit_limit",
        "current_balance",
        "card_status",
    }.issubset(set(df.columns))


def test_write_iceberg_creates_table(spark):
    df = read_cc_records(spark, DATA_PATH)
    written = write_iceberg(df)
    assert written == 5
    result = spark.table(f"{CATALOG}.{NAMESPACE}.{TABLE_NAME}")
    assert result.count() == written


def test_read_iceberg_data(spark):
    print("Iceberg data")
    result = spark.read.format("iceberg").load(f"{CATALOG}.{NAMESPACE}.{TABLE_NAME}")
    print(result.columns)
    print(result.count())
    result.show(3)
    assert result.count() >= 5


def test_write_iceberg_data_integrity(spark):
    df = read_cc_records(spark, DATA_PATH)
    write_iceberg(df)
    spark.read.format("iceberg").load(f"{CATALOG}.{NAMESPACE}.{TABLE_NAME}").show()
    result = spark.table(f"{CATALOG}.{NAMESPACE}.{TABLE_NAME}")
    statuses = {row["card_status"] for row in result.select("card_status").distinct().collect()}
    assert statuses.issubset(VALID_STATUSES)
    assert result.filter(result["credit_limit"] <= 0).count() == 0
    assert result.filter(result["current_balance"] < 0).count() == 0
    assert result.filter(result["currency"].isNull()).count() == 0
    assert result.select("card_id").distinct().count() == 5
