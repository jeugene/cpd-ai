import os
import sys

os.environ["PYSPARK_PYTHON"] = sys.executable
os.environ["PYSPARK_DRIVER_PYTHON"] = sys.executable

from core.spark_utils import get_spark_session

# Build local spark session
spark = get_spark_session("spark-verify")

# Create dummy data
data = [
    ("Cards", 2026),
    ("Books", 2015),
    ("Book3", 2010),
    ("Book4", 2021),
    ("Book5", 2017),
]
spark.createDataFrame(data, ["Book", "Year"]).orderBy("Year", ascending=False).show()

spark.table("local.cpd.credit_cards").show(5)
spark.stop()
