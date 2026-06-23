import logging
import os
from pathlib import Path

from pyspark.sql import SparkSession

_HERE = Path(__file__).resolve().parent.parent.parent.parent.parent
LOG_DIR = _HERE / "logs"

# Local-mode paths (kept as module-level constants for test fixture backward compat)
WAREHOUSE_PATH = _HERE / "tmp"
WAREHOUSE = WAREHOUSE_PATH.as_posix()
ICEBERG_JAR = str(Path(
    r"C:\jeugene\_work\software\py-jars\org.apache.iceberg_iceberg-spark-runtime-4.1_2.13-1.11.0.jar"
))
SPARK_DIR = WAREHOUSE_PATH / "spark"

_ICEBERG_EXTENSIONS = "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions"
_ICEBERG_CATALOG = "org.apache.iceberg.spark.SparkCatalog"
_GLUE_CATALOG_IMPL = "org.apache.iceberg.aws.glue.GlueCatalog"
_S3_FILE_IO = "org.apache.iceberg.aws.s3.S3FileIO"


def _setup_logging() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    if not logging.getLogger().handlers:
        logging.basicConfig(
            level=os.getenv("LOG_LEVEL", "INFO").upper(),
            format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
            handlers=[
                logging.FileHandler(LOG_DIR / "spark.log", encoding="utf-8"),
                logging.StreamHandler(),
            ],
        )


def _ensure_hadoop_on_path() -> None:
    hadoop_home = os.environ.get("HADOOP_HOME", "")
    if not hadoop_home:
        return
    hadoop_bin = str(Path(hadoop_home) / "bin")
    path = os.environ.get("PATH", "")
    if hadoop_bin.lower() not in path.lower():
        os.environ["PATH"] = hadoop_bin + os.pathsep + path


def _spark_mode() -> str:
    from .config import config  # lazy — avoids boto3 import at module level

    return config().get("spark", "mode", fallback="local").lower()


def _s3_warehouse() -> str:
    from .config import config

    cfg = config()
    explicit = cfg.get("spark", "warehouse_s3", fallback="").strip()
    if explicit:
        return explicit
    return cfg.get("app", "s3Data", fallback="s3://change-me") + "/warehouse"


def _local_session(app_name: str) -> SparkSession:
    _ensure_hadoop_on_path()
    WAREHOUSE_PATH.mkdir(parents=True, exist_ok=True)
    return (
        SparkSession.builder.appName(app_name)
        .master("local[*]")
        .config("spark.driver.extraClassPath", ICEBERG_JAR)
        .config("spark.sql.extensions", _ICEBERG_EXTENSIONS)
        .config("spark.sql.catalog.local", _ICEBERG_CATALOG)
        .config("spark.sql.catalog.local.type", "hadoop")
        .config("spark.sql.catalog.local.warehouse", WAREHOUSE)
        .config("spark.local.dir", SPARK_DIR.as_posix())
        .config("spark.sql.shuffle.partitions", "2")
        .config("spark.driver.memory", "1g")
        .config("spark.ui.enabled", "false")
        .config("spark.driver.bindAddress", "127.0.0.1")
        .config("spark.driver.host", "127.0.0.1")
        .getOrCreate()
    )


def _emr_session(app_name: str) -> SparkSession:
    """EMR: master/executor config comes from YARN; Iceberg JAR via EMR bootstrap."""
    warehouse = _s3_warehouse()
    return (
        SparkSession.builder.appName(app_name)
        .config("spark.sql.extensions", _ICEBERG_EXTENSIONS)
        .config("spark.sql.catalog.local", _ICEBERG_CATALOG)
        .config("spark.sql.catalog.local.type", "hadoop")
        .config("spark.sql.catalog.local.io-impl", _S3_FILE_IO)
        .config("spark.sql.catalog.local.warehouse", warehouse)
        .getOrCreate()
    )


def _glue_session(app_name: str) -> SparkSession:
    """Glue: GlueContext owns the SparkContext; Iceberg uses the Glue Data Catalog."""
    from awsglue.context import GlueContext  # type: ignore[import-untyped]
    from pyspark import SparkConf, SparkContext

    warehouse = _s3_warehouse()
    # App name can only be set before SC is created; on Glue the job name is used instead.
    conf = SparkConf().setAppName(app_name)
    sc = SparkContext.getOrCreate(conf)
    glue_ctx = GlueContext(sc)
    spark = glue_ctx.spark_session
    spark.conf.set("spark.sql.extensions", _ICEBERG_EXTENSIONS)
    spark.conf.set("spark.sql.catalog.local", _ICEBERG_CATALOG)
    spark.conf.set("spark.sql.catalog.local.catalog-impl", _GLUE_CATALOG_IMPL)
    spark.conf.set("spark.sql.catalog.local.io-impl", _S3_FILE_IO)
    spark.conf.set("spark.sql.catalog.local.warehouse", warehouse)
    return spark


def get_spark_session(app_name: str = "cpd") -> SparkSession:
    _setup_logging()
    logger = logging.getLogger(__name__)
    mode = _spark_mode()
    logger.info("Initializing SparkSession [mode=%s]: %s", mode, app_name)
    if mode == "glue":
        spark = _glue_session(app_name)
    elif mode == "emr":
        spark = _emr_session(app_name)
    else:
        spark = _local_session(app_name)
    spark.sparkContext.setLogLevel("WARN")
    logger.info("SparkSession ready [mode=%s]", mode)
    return spark
