# CPD AI
A reference implementation for an AI-powered credit card solution in Python, Java, and Rust. Features an AI agent for card scoring and ranking, a Spark + Iceberg ETL pipeline for data ingestion, and a Retrieval-Augmented Generation (RAG) system.

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
# Execute this to pull the required libraries and python spark dependencies
mvn clean package   # Java
poetry install      # Python

```

## Run

```bash
# Python
poetry run python src/main/python/credit_cards.py          # Spark ETL
poetry run python src/main/python/agents/cards_agent.py    # research agent
poetry run python src/main/python/rag/ingest.py            # RAG ingest

# Java
TBD

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

> **TODO:** Merge `src/main/python/core/config.ini` and `src/main/resources/app.properties` into a single unified configuration source shared across Python, Java, and Rust.

### AWS

| Key | Default | Description |
|---|---|---|
| `awsEnv` | `dev` | Deployment environment |
| `awsRegion` | `us-east-1` | AWS region |
| `awsAccount` | — | AWS account ID |
| `appCode` | `cpd` | App prefix used in resource names |

Config file: `src/main/python/core/config.ini` `[app]`

### AI

| Key | Default | Description |
|---|---|---|
| `provider` | `anthropic` | `anthropic` \| `openai` \| `copilot` \| `bedrock` |
| `anthropic_model` | `claude-sonnet-4-6` | Override Anthropic model |
| `openai_model` | `gpt-4o` | Override OpenAI model |
| `bedrock_model` | `claude-opus-4-8` | Override Bedrock model |
| `embed_model` | `titan-embed-text-v2` | Bedrock embedding model |
| `gen_model` | `claude-opus-4-8` | Bedrock generation model |
| `chunk_size` | `500` | RAG chunk size (chars) |
| `top_k` | `5` | RAG nearest neighbours |

Auth: set `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, or `GITHUB_TOKEN` depending on provider.

### Java

| Key | Default | Description |
|---|---|---|
| `spark.master` | `local[2]` | Spark master URL |
| `spark.warehouse.path` | `tmp/warehouse` | Local Iceberg warehouse |
| `iceberg.catalog.name` | `local` | Iceberg catalog |
| `iceberg.namespace` | `cpd` | Iceberg database |

Config file: `src/main/resources/app.properties`

### Python

| Key | Default | Description |
|---|---|---|
| `mode` | `local` | `local` \| `emr` \| `glue` |
| `catalog` | `local` | Iceberg catalog name |
| `namespace` | `cpd` | Iceberg database |
| `cards` | `src/main/resources/credit_cards.json` | Source data path |

Config file: `src/main/python/core/config.ini` `[spark]`

### Rust

| Env var | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic API key (default provider) |

## Code tree

```
src/
├── main/
│   ├── python/
│   │   ├── core/       # config, AWS client factories
│   │   ├── agents/     # cards agent, ranker, LLM providers
│   │   └── rag/        # embedder, ingest, vector store, query pipeline
│   ├── java/com/cloudpid/ai/
│   │   ├── config/     # AppConfig (@owner-backed)
│   │   ├── aws/        # S3, SQS, Glue, Lambda services
│   │   ├── agents/     # CardsAgent, CardRanker, Provider
│   │   ├── spark/      # CreditCardJob, SparkFactory
│   │   └── rag/        # Embedder, Ingestor, RagPipeline
│   ├── rust/src/
│   │   └── agents/     # AnthropicProvider, CardsAgent, ranker
│   └── resources/      # credit_cards.json, log4j2.xml
└── test/
    ├── python/
    └── java/com/cloudpid/ai/
```
