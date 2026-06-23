package com.cloudpid.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Amazon S3 Vectors client wrapper.
 * Mirrors Python {@code rag/vector_store.py}.
 */
public final class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private VectorStore() {}

    /**
     * Store pre-embedded chunks in the vector bucket.
     * Mirrors Python {@code put_chunks(chunks, *, vector_bucket, s3vectors_client)}.
     *
     * @return number of chunks stored
     */
    public static int putChunks(List<VectorChunk> chunks, String vectorBucket, S3VectorsClient client) {
        if (chunks.isEmpty()) return 0;

        List<PutInputVector> vectors = chunks.stream()
            .map(c -> PutInputVector.builder()
                .key(c.key())
                .data(d -> d.float32(c.vector()))
                .metadata(toDocument(c.metadata()))
                .build())
            .collect(Collectors.toList());

        client.putVectors(PutVectorsRequest.builder()
            .vectorBucketName(vectorBucket)
            .vectors(vectors)
            .build());

        log.info("Stored {} vectors in bucket {}", chunks.size(), vectorBucket);
        return chunks.size();
    }

    /**
     * ANN search against the vector bucket, returning metadata of top-k hits.
     * Mirrors Python {@code query_chunks(query_vector, *, top_k, vector_bucket, s3vectors_client)}.
     */
    public static List<Map<String, Object>> queryChunks(
            List<Float> queryVector, int topK, String vectorBucket, S3VectorsClient client) {

        QueryVectorsResponse response = client.queryVectors(QueryVectorsRequest.builder()
            .vectorBucketName(vectorBucket)
            .queryVector(qv -> qv.float32(queryVector))
            .topK(topK)
            .returnMetadata(true)
            .build());

        log.debug("ANN query returned {} hits (top_k={})", response.vectors().size(), topK);
        return response.vectors().stream()
            .map(v -> fromDocument(v.metadata()))
            .collect(Collectors.toList());
    }

    // ── Document conversion ────────────────────────────────────────────────────

    static Document toDocument(Map<String, Object> map) {
        Map<String, Document> docMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s) {
                docMap.put(entry.getKey(), Document.fromString(s));
            } else if (val instanceof Integer i) {
                docMap.put(entry.getKey(), Document.fromNumber(i));
            } else if (val instanceof Long l) {
                docMap.put(entry.getKey(), Document.fromNumber(l));
            } else if (val instanceof Double d) {
                docMap.put(entry.getKey(), Document.fromNumber(d));
            } else if (val instanceof Float f) {
                docMap.put(entry.getKey(), Document.fromNumber(f));
            } else if (val instanceof BigDecimal bd) {
                docMap.put(entry.getKey(), Document.fromNumber(bd));
            } else if (val instanceof Boolean b) {
                docMap.put(entry.getKey(), Document.fromBoolean(b));
            } else if (val != null) {
                docMap.put(entry.getKey(), Document.fromString(val.toString()));
            }
        }
        return Document.fromMap(docMap);
    }

    static Map<String, Object> fromDocument(Document doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (doc == null || !doc.isMap()) return result;
        for (Map.Entry<String, Document> entry : doc.asMap().entrySet()) {
            Document val = entry.getValue();
            if (val.isString()) {
                result.put(entry.getKey(), val.asString());
            } else if (val.isNumber()) {
                SdkNumber n = val.asNumber();
                BigDecimal bd = n.bigDecimalValue();
                result.put(entry.getKey(), bd.scale() == 0 ? n.intValue() : n.doubleValue());
            } else if (val.isBoolean()) {
                result.put(entry.getKey(), val.asBoolean());
            } else {
                result.put(entry.getKey(), val.toString());
            }
        }
        return result;
    }
}
