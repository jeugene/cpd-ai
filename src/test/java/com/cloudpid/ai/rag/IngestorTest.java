package com.cloudpid.ai.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.PutVectorsResponse;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestorTest {

    private static final String BUCKET = "src-bucket";
    private static final String KEY = "docs/report.txt";
    private static final String VECTOR_BUCKET = "vec-bucket";
    private static final int CHUNK_SIZE = 500;
    private static final int OVERLAP = 50;

    @Mock S3Client s3;
    @Mock Embedder embedder;
    @Mock S3VectorsClient s3Vectors;

    Ingestor ingestor;

    @BeforeEach
    void setUp() {
        ingestor = new Ingestor(s3, embedder, s3Vectors, CHUNK_SIZE, OVERLAP);
    }

    @SuppressWarnings("unchecked")
    private void stubS3Text(String content) {
        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class)))
            .thenAnswer(inv -> ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                content.getBytes(StandardCharsets.UTF_8)));
    }

    private void stubEmbedder() {
        when(embedder.embedText(any())).thenReturn(Collections.nCopies(1024, 0.1f));
    }

    private void stubS3Vectors() {
        when(s3Vectors.putVectors(any(PutVectorsRequest.class)))
            .thenReturn(PutVectorsResponse.builder().build());
    }

    // ── ingestDocument ────────────────────────────────────────────────────────

    @Test
    void ingestDocument_returnsChunkCount() {
        stubS3Text("word ".repeat(200));
        stubEmbedder();
        stubS3Vectors();

        int count = ingestor.ingestDocument(BUCKET, KEY, VECTOR_BUCKET);
        assertTrue(count >= 1);
    }

    @Test
    void ingestDocument_callsEmbedPerChunk() {
        String text = "a".repeat(1200);
        stubS3Text(text);
        stubEmbedder();
        stubS3Vectors();

        ingestor.ingestDocument(BUCKET, KEY, VECTOR_BUCKET);

        int expected = TextChunker.chunkText(text, CHUNK_SIZE, OVERLAP).size();
        verify(embedder, times(expected)).embedText(any());
    }

    @Test
    void ingestDocument_storesMetadataWithText() {
        stubS3Text("Banking document content");
        stubEmbedder();
        stubS3Vectors();

        ingestor.ingestDocument(BUCKET, KEY, VECTOR_BUCKET);

        ArgumentCaptor<PutVectorsRequest> cap = ArgumentCaptor.forClass(PutVectorsRequest.class);
        verify(s3Vectors).putVectors(cap.capture());
        var meta = VectorStore.fromDocument(cap.getValue().vectors().get(0).metadata());

        assertEquals(BUCKET, meta.get("source_bucket"));
        assertEquals(KEY, meta.get("source_key"));
        assertEquals(0, ((Number) meta.get("chunk_index")).intValue());
        assertNotNull(meta.get("text"));
    }

    // ── ingestS3Prefix ────────────────────────────────────────────────────────

    @Test
    void ingestS3Prefix_totalChunks() {
        stubS3List(List.of("docs/a.txt", "docs/b.txt"));
        stubS3Text("short text");
        stubEmbedder();
        stubS3Vectors();

        int total = ingestor.ingestS3Prefix(BUCKET, "docs/", VECTOR_BUCKET);
        assertTrue(total >= 2);
    }

    @Test
    void ingestS3Prefix_skipsFailedDocuments() {
        stubS3List(List.of("good.txt", "bad.txt"));
        stubEmbedder();
        stubS3Vectors();

        // good.txt returns content; bad.txt throws
        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class)))
            .thenAnswer(inv -> ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                "content".getBytes(StandardCharsets.UTF_8)))
            .thenThrow(new RuntimeException("S3 read error"));

        int total = ingestor.ingestS3Prefix(BUCKET, "", VECTOR_BUCKET);
        assertTrue(total >= 1);
        verify(s3, times(2)).getObject(any(Consumer.class), any(ResponseTransformer.class));
    }

    // ── listS3Keys ────────────────────────────────────────────────────────────

    @Test
    void listS3Keys_returnsKeys() {
        stubS3List(List.of("docs/a.txt", "docs/b.txt"));
        List<String> keys = ingestor.listS3Keys(BUCKET, "docs/");
        assertEquals(List.of("docs/a.txt", "docs/b.txt"), keys);
    }

    @Test
    void listS3Keys_emptyPrefix() {
        stubS3List(List.of());
        assertEquals(List.of(), ingestor.listS3Keys(BUCKET, ""));
    }

    // ── readS3Text ────────────────────────────────────────────────────────────

    @Test
    void readS3Text_returnsString() {
        stubS3Text("hello world");
        assertEquals("hello world", ingestor.readS3Text(BUCKET, KEY));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubS3List(List<String> keys) {
        List<S3Object> objects = keys.stream()
            .map(k -> S3Object.builder().key(k).build())
            .toList();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(ListObjectsV2Response.builder()
                .contents(objects)
                .isTruncated(false)
                .build());
    }
}
