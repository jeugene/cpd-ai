import logging

from core import config

cfg = config.config()
log = logging.getLogger()


def test_config():
    assert cfg["app"]["awsAccount"] == "1234567890"
    assert cfg["app"]["awsRegion"] == "us-east-1"
    assert cfg["app"]["awsEnv"] == "dev"
    assert cfg["app"]["s3Data"] == "s3://cpd-data-dev-us-east-1-1234567890"


def test_setup_config():
    assert cfg["app"]["awsAccount"] == "1234567890"
    assert cfg["app"]["awsRegion"] == "us-east-1"


def test_setup_logging():
    level = logging.getLevelName(log.getEffectiveLevel())
    assert level == "INFO"
