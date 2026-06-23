import boto3
from botocore.config import Config

from .config import config

cfg = config()


def get_config():
    return cfg


def sts_api():
    return boto3.client("sts", region_name=cfg["app"]["awsRegion"])


def s3_api():
    return boto3.client(
        "s3",
        region_name=cfg["app"]["awsRegion"],
        config=Config(s3={"us_east_1_regional_endpoint": "regional"}),
    )


def lambda_api():
    return boto3.client("lambda", region_name=cfg["app"]["awsRegion"])


def glue_api():
    return boto3.client("glue", region_name=cfg["app"]["awsRegion"])


def redshift_api():
    return boto3.client("redshift-data", region_name=cfg["app"]["awsRegion"])


def bedrock_runtime_api():
    return boto3.client("bedrock-runtime", region_name=cfg["app"]["awsRegion"])


def s3vectors_api():
    return boto3.client("s3vectors", region_name=cfg["app"]["awsRegion"])
