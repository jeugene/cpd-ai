from core import aws

cfg = aws.get_config()
region = cfg["app"]["awsRegion"]


def test_sts_api():
    api = aws.sts_api()
    assert api is not None
    assert api.meta.service_model.service_name == "sts"


def test_s3_api():
    api = aws.s3_api()
    assert api._endpoint.host.find(region) > 3
    assert api is not None


def test_lambda_api():
    api = aws.lambda_api()
    assert api._endpoint.host.find(region) > 3
    assert api is not None


def test_glue_api():
    api = aws.glue_api()
    assert api._endpoint.host.find(region) > 3
    assert api is not None


def test_redshift_api():
    api = aws.redshift_api()
    assert api._endpoint.host.find(region) > 3
    assert api is not None
