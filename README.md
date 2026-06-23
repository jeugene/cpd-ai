# cpd-ai
A reference implementation for an AI-powered credit card platform in Python, Java, and Rust. Features an autonomous web-research agent for card scoring and ranking, a Spark + Iceberg ETL pipeline for data ingestion, and a Retrieval-Augmented Generation (RAG) system.

## Prerequisites

| Tool | Version |
|---|---|
| Java | 21 |
| Maven | 3.9+ |
| Python | 3.12+ |
| Poetry | 2.0+ |
| Rust / Cargo | 1.75+ |

**Rust on Windows:** add WinLibs MinGW `bin/` to `PATH` before running `cargo`.

## Setup

```bash
poetry install      # Python
mvn clean package   # Java
```

## Run

```bash
# Python
poetry run python src/main/python/credit_cards.py          # Spark ETL
poetry run python src/main/python/agents/cards_agent.py    # research agent
poetry run python src/main/python/rag/ingest.py            # RAG ingest

# Java
java -jar target/cpd-ai-1.0.0-SNAPSHOT.jar

# Rust
cd src/main/rust && cargo run --release
```

Set `ANTHROPIC_API_KEY` (or provider-appropriate key) before running the agent.

## Test

```bash
poetry run pytest src/test/python/ -v   # Python
mvn test                                 # Java
cd src/main/rust && cargo test           # Rust
```

## Lint

```bash
poetry run ruff check src/main/python/ src/test/python/
poetry run ruff check --fix src/main/python/ src/test/python/
```

## Configuration

`src/main/python/core/config.ini` — AWS env, Spark mode (`local`/`emr`/`glue`), Iceberg catalog, and LLM provider (`anthropic`/`openai`/`copilot`/`bedrock`).
