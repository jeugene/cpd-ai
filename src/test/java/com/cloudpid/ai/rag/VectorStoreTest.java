package com.cloudpid.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorStoreTest {

    private static final String BUCKET = "test-vectors";

    @Mock
    S3VectorsClient s3Vectors;

    private VectorChunk sampleChunk(String text, int idx) {
        return new VectorChunk(
            "chunk-" + idx,
            Collections.nCopies(1024, 0.1f),
            Map.of("source_bucket", "my-bucket", "source_key", "doc.txt",
                   "chunk_index", idx, "text", text)
        );
    }

    // ── putChunks ─────────────────────────────────────────────────────────────

    @Test
    void putChunks_returnsCount() {
        VectorChunk c0 = sampleChunk("a", 0);
        VectorChunk c1 = sampleChunk("b", 1);
        assertEquals(2, VectorStore.putChunks(List.of(c0, c1), BUCKET, s3Vectors));
    }

    @Test
    void putChunks_emptyReturnsZeroWithoutCall() {
        assertEquals(0, VectorStore.putChunks(List.of(), BUCKET, s3Vectors));
        verify(s3Vectors, never()).putVectors((PutVectorsRequest) any());
    }

    @Test
    void putChunks_formatsPayloadCorrectly() {
        VectorChunk chunk = sampleChunk("hello", 3);
        VectorStore.putChunks(List.of(chunk), BUCKET, s3Vectors);

        ArgumentCaptor<PutVectorsRequest> cap = ArgumentCaptor.forClass(PutVectorsRequest.class);
        verify(s3Vectors).putVectors((PutVectorsRequest) cap.capture());
        PutVectorsRequest req = cap.getValue();

        assertEquals(BUCKET, req.vectorBucketName());
        assertEquals(1, req.vectors().size());

        PutInputVector vec = req.vectors().get(0);
        assertEquals(chunk.key(), vec.key());
        assertEquals(chunk.vector(), vec.data().float32());
    }

    @Test
    void putChunks_storesMetadata() {
        VectorChunk chunk = sampleChunk("content", 0);
        VectorStore.putChunks(List.of(chunk), BUCKET, s3Vectors);

        ArgumentCaptor<PutVectorsRequest> cap = ArgumentCaptor.forClass(PutVectorsRequest.class);
        verify(s3Vectors).putVectors((PutVectorsRequest) cap.capture());
        Document meta = cap.getValue().vectors().get(0).metadata();
        assertNotNull(meta);
        assertTrue(meta.isMap());
        assertEquals("my-bucket", meta.asMap().get("source_bucket").asString());
    }

    // ── queryChunks ───────────────────────────────────────────────────────────

    @Test
    void queryChunks_returnsMetadataList() {
        Document meta = VectorStore.toDocument(
            Map.of("source_key", "doc.txt", "chunk_index", 0, "text", "Banking is great"));
        QueryVectorsResponse resp = QueryVectorsResponse.builder()
            .vectors(List.of(QueryOutputVector.builder().key("k").distance(0.95f).metadata(meta).build()))
            .build();
        when(s3Vectors.queryVectors(any(QueryVectorsRequest.class))).thenReturn(resp);

        List<Map<String, Object>> result =
            VectorStore.queryChunks(Collections.nCopies(1024, 0.1f), 1, BUCKET, s3Vectors);

        assertEquals(1, result.size());
        assertEquals("doc.txt", result.get(0).get("source_key"));
        assertEquals("Banking is great", result.get(0).get("text"));
    }

    @Test
    void queryChunks_emptyResponseReturnsEmptyList() {
        when(s3Vectors.queryVectors(any(QueryVectorsRequest.class)))
            .thenReturn(QueryVectorsResponse.builder().vectors(List.of()).build());

        List<Map<String, Object>> result =
            VectorStore.queryChunks(Collections.nCopies(1024, 0.1f), 5, BUCKET, s3Vectors);

        assertTrue(result.isEmpty());
    }

    @Test
    void queryChunks_sendsCorrectPayload() {
        when(s3Vectors.queryVectors(any(QueryVectorsRequest.class)))
            .thenReturn(QueryVectorsResponse.builder().vectors(List.of()).build());

        List<Float> vec = Collections.nCopies(1024, 0.2f);
        VectorStore.queryChunks(vec, 3, BUCKET, s3Vectors);

        ArgumentCaptor<QueryVectorsRequest> cap = ArgumentCaptor.forClass(QueryVectorsRequest.class);
        verify(s3Vectors).queryVectors(cap.capture());
        QueryVectorsRequest req = cap.getValue();

        assertEquals(BUCKET, req.vectorBucketName());
        assertEquals(3, req.topK());
        assertTrue(req.returnMetadata());
        assertEquals(vec, req.queryVector().float32());
    }

    // ── Document round-trip ───────────────────────────────────────────────────

    @Test
    void documentRoundTrip_preservesStringAndInt() {
        Map<String, Object> original = Map.of(
            "source_key", "file.txt",
            "chunk_index", 2,
            "text", "some content"
        );
        Document doc = VectorStore.toDocument(original);
        Map<String, Object> recovered = VectorStore.fromDocument(doc);

        assertEquals("file.txt", recovered.get("source_key"));
        assertEquals(2, ((Number) recovered.get("chunk_index")).intValue());
        assertEquals("some content", recovered.get("text"));
    }
}
