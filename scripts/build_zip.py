"""
Build a Lambda deployment zip.

Usage:
    python scripts/build_lambda.py
    poetry run build-lambda

Output:
    dist/cpd-ai.zip

Lambda handler:
    sqs_to_s3.handler
"""

import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAGE = ROOT / "dist" / "lambda"
ZIP_PATH = ROOT / "dist" / "cpd-ai.zip"

_IGNORE = shutil.ignore_patterns(
    "__pycache__", "*.pyc", "*.pyo",
    "tmp", "*.crc", "*.parquet", "*.avro",
)


def _copy_source(stage: Path) -> None:
    shutil.copytree(ROOT / "src" / "main" / "python", stage, ignore=_IGNORE, dirs_exist_ok=True)


def _zip(stage: Path, dest: Path) -> None:
    if dest.exists():
        dest.unlink()
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for path in sorted(stage.rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts:
                zf.write(path, path.relative_to(stage))


def main() -> None:
    print("Building Lambda deployment package...")

    if STAGE.exists():
        shutil.rmtree(STAGE)
    STAGE.mkdir(parents=True)
    ZIP_PATH.parent.mkdir(exist_ok=True)

    print("  Copying source tree (src/main/python/)...")
    _copy_source(STAGE)

    print(f"  Zipping -> dist/{ZIP_PATH.name}")
    _zip(STAGE, ZIP_PATH)

    size_mb = ZIP_PATH.stat().st_size / (1024**2)
    print(f"\nLambda package : dist/{ZIP_PATH.name}  ({size_mb:.1f} MB)")
    print("Handler        : sqs_to_s3.handler")


if __name__ == "__main__":
    main()
