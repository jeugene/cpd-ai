package com.cloudpid.ai.rag;

import com.cloudpid.ai.config.AppConfig;
import com.cloudpid.ai.config.AppConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3 text ingestion pipeline: read → chunk → embed → store in S3 Vectors.
 * Mirrors Python {@code rag/ingest.py}.
 */
public final class Ingestor {

    private static final Logger log = LoggerFactory.getLogger(Ingestor.class);

    private final S3Client s3;
    private final Embedder embedder;
    private final S3VectorsClient s3Vectors;
    private final int chunkSize;
    private final int overlap;

    public Ingestor(S3Client s3, Embedder embedder, S3VectorsClient s3Vectors,
                    int chunkSize, int overlap) {
        this.s3 = s3;
        this.embedder = embedder;
        this.s3Vectors = s3Vectors;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public static Ingestor create() {
        AppConfig cfg = AppConfigFactory.get();
        S3Client s3 = S3Client.builder().region(Region.of(cfg.awsRegion())).build();
        S3VectorsClient s3v = S3VectorsClient.builder().region(Region.of(cfg.awsRegion())).build();
        return new Ingestor(s3, Embedder.create(), s3v, cfg.ragChunkSize(), cfg.ragChunkOverlap());
    }

    /**
     * Ingest a single S3 text document: read → chunk → embed → store.
     * Mirrors Python {@code ingest_document(bucket, key, ...)}.
     *
     * @return number of chunks stored
     */
    public int ingestDocument(String bucket, String key, String vectorBucket) {
        log.info("Ingesting s3://{}/{}", bucket, key);
        String text = readS3Text(bucket, key);
        List<String> chunksText = TextChunker.chunkText(text, chunkSize, overlap);
        log.debug("Split into {} chunks", chunksText.size());

        List<VectorChunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunksText.size(); i++) {
            String chunk = chunksText.get(i);
            List<Float> vector = embedder.embedText(chunk);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_bucket", bucket);
            metadata.put("source_key", key);
            metadata.put("chunk_index", i);
            metadata.put("text", chunk);
            chunks.add(new VectorChunk(TextChunker.chunkKey(key, i), vector, metadata));
        }

        return VectorStore.putChunks(chunks, vectorBucket, s3Vectors);
    }

    /**
     * Ingest all text objects under an S3 prefix.
     * Mirrors Python {@code ingest_s3_prefix(bucket, prefix, ...)}.
     *
     * @return total chunks stored across all documents
     */
    public int ingestS3Prefix(String bucket, String prefix, String vectorBucket) {
        List<String> keys = listS3Keys(bucket, prefix);
        log.info("Found {} objects under s3://{}/{}", keys.size(), bucket, prefix);
        int total = 0;
        for (String key : keys) {
            try {
                total += ingestDocument(bucket, key, vectorBucket);
            } catch (Exception e) {
                log.error("Failed to ingest s3://{}/{} — skipping", bucket, key, e);
            }
        }
        return total;
    }

    // ── package-private for testing ────────────────────────────────────────────

    String readS3Text(String bucket, String key) {
        return s3.getObject(
            r -> r.bucket(bucket).key(key),
            software.amazon.awssdk.core.sync.ResponseTransformer.toBytes()
        ).asUtf8String();
    }

    List<String> listS3Keys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix);
            if (continuationToken != null) req.continuationToken(continuationToken);
            ListObjectsV2Response page = s3.listObjectsV2(req.build());
            for (S3Object obj : page.contents()) keys.add(obj.key());
            continuationToken = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (continuationToken != null);
        return keys;
    }
}
