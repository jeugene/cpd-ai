import logging
import os
from configparser import ConfigParser, ExtendedInterpolation
from pathlib import Path

import boto3
from botocore.exceptions import ClientError

_cfg = None
log = logging.getLogger()


def _setup_config(cfg: ConfigParser):
    region = boto3.Session().region_name
    if region:
        cfg.set("app", "awsRegion", region)
    try:
        sts = boto3.client("sts", region_name=cfg["app"]["awsRegion"])
        response = sts.get_caller_identity()
        cfg.set("app", "awsAccount", response["Account"])
    except ClientError:
        log.warning("AWS keys are missing")


def _setup_logging() -> None:
    level = os.getenv("logLevel", "INFO").upper()
    logging.basicConfig(
        level=level,
        force=True,
        encoding="utf-8",
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )


def config():
    _setup_logging()
    global _cfg
    if _cfg is None:
        _cfg = ConfigParser(interpolation=ExtendedInterpolation())
        ini = str(Path(__file__).parent.resolve()) + "/config.ini"
        _cfg.read(ini)
        _setup_config(_cfg)
    return _cfg


def _reload() -> bool:
    global _cfg
    _cfg = None
    config()
    return True
