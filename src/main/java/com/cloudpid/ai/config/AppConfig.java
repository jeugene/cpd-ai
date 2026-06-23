package com.cloudpid.ai.config;

import org.aeonbits.owner.Config;
import org.aeonbits.owner.Config.DefaultValue;
import org.aeonbits.owner.Config.Key;
import org.aeonbits.owner.Config.Sources;

@Sources({
        "classpath:app.properties",
        "system:properties",
        "system:env"
})
public interface AppConfig extends Config {

    @Key("aws.region")
    @DefaultValue("us-east-1")
    String awsRegion();

    @Key("aws.s3.bucket")
    String s3Bucket();

    @Key("aws.sqs.queue.url")
    String sqsQueueUrl();

    @Key("aws.lambda.function.name")
    String lambdaFunctionName();

    @Key("aws.glue.database")
    String glueDatabaseName();

    @Key("spark.app.name")
    @DefaultValue("cpd-app")
    String sparkAppName();

    @Key("spark.master")
    @DefaultValue("local[2]")
    String sparkMaster();

    @Key("spark.warehouse.path")
    @DefaultValue("tmp/warehouse")
    String sparkWarehousePath();

    @Key("iceberg.catalog.name")
    @DefaultValue("local")
    String icebergCatalogName();

    @Key("iceberg.namespace")
    @DefaultValue("cpd")
    String icebergNamespace();

    // ── RAG ───────────────────────────────────────────────────────────────────

    @Key("rag.vector.bucket")
    @DefaultValue("rag-vectors")
    String ragVectorBucket();

    @Key("rag.embed.model")
    @DefaultValue("amazon.titan-embed-text-v2:0")
    String ragEmbedModel();

    @Key("rag.gen.model")
    @DefaultValue("us.anthropic.claude-opus-4-8-20251101")
    String ragGenModel();

    @Key("rag.embed.dims")
    @DefaultValue("1024")
    int ragEmbedDims();

    @Key("rag.chunk.size")
    @DefaultValue("500")
    int ragChunkSize();

    @Key("rag.chunk.overlap")
    @DefaultValue("50")
    int ragChunkOverlap();

    @Key("rag.top.k")
    @DefaultValue("5")
    int ragTopK();

    // ── Agents / providers ────────────────────────────────────────────────────

    @Key("agents.provider")
    @DefaultValue("anthropic")
    String agentsProvider();

    @Key("agents.anthropic.model")
    @DefaultValue("claude-sonnet-4-6")
    String agentsAnthropicModel();

    @Key("agents.openai.model")
    @DefaultValue("gpt-4o")
    String agentsOpenaiModel();

    @Key("agents.copilot.model")
    @DefaultValue("gpt-4o")
    String agentsCopilotModel();

    @Key("agents.bedrock.model")
    @DefaultValue("us.anthropic.claude-opus-4-8-20251101")
    String agentsBedrockModel();
}
