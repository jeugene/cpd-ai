package com.cloudpid.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.QueryOutputVector;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagPipelineTest {

    private static final String VECTOR_BUCKET = "test-vectors";

    @Mock Embedder embedder;
    @Mock S3VectorsClient s3Vectors;
    @Mock RagGenerator generator;

    private RagPipeline pipeline(int topK) {
        return new RagPipeline(embedder, s3Vectors, generator, topK, VECTOR_BUCKET);
    }

    private void stubEmbedder() {
        when(embedder.embedText(anyString())).thenReturn(Collections.nCopies(1024, 0.3f));
    }

    private void stubVectorStore(List<Map<String, Object>> chunks) {
        List<QueryOutputVector> hits = chunks.stream().map(meta ->
            QueryOutputVector.builder()
                .key("k")
                .distance(0.9f)
                .metadata(VectorStore.toDocument(meta))
                .build()
        ).toList();
        when(s3Vectors.queryVectors(any(QueryVectorsRequest.class)))
            .thenReturn(QueryVectorsResponse.builder().vectors(hits).build());
    }

    // ── buildPrompt (pure) ────────────────────────────────────────────────────

    @Test
    void buildPrompt_containsQuestion() {
        String prompt = RagPipeline.buildPrompt("What is APR?", List.of());
        assertTrue(prompt.contains("What is APR?"));
    }

    @Test
    void buildPrompt_containsContextText() {
        var chunk = Map.<String, Object>of(
            "source_key", "doc.txt", "chunk_index", 0,
            "text", "APR stands for Annual Percentage Rate");
        assertTrue(RagPipeline.buildPrompt("What is APR?", List.of(chunk))
            .contains("APR stands for Annual Percentage Rate"));
    }

    @Test
    void buildPrompt_emptyContextIsStillValid() {
        String prompt = RagPipeline.buildPrompt("any question", List.of());
        assertTrue(prompt.contains("any question"));
    }

    @Test
    void buildPrompt_includesSourceAttribution() {
        var chunk = Map.<String, Object>of(
            "source_key", "reports/q1.txt", "chunk_index", 2, "text", "content");
        String prompt = RagPipeline.buildPrompt("question", List.of(chunk));
        assertTrue(prompt.contains("reports/q1.txt"));
        assertTrue(prompt.contains("2"));
    }

    @Test
    void buildPrompt_multipleChunksSeparatedByDivider() {
        var c1 = Map.<String, Object>of("source_key", "a.txt", "chunk_index", 0, "text", "First chunk");
        var c2 = Map.<String, Object>of("source_key", "b.txt", "chunk_index", 0, "text", "Second chunk");
        String prompt = RagPipeline.buildPrompt("q?", List.of(c1, c2));
        assertTrue(prompt.contains("First chunk"));
        assertTrue(prompt.contains("Second chunk"));
        assertTrue(prompt.contains("---"));
    }

    // ── answerQuestion (end-to-end, all mocked) ───────────────────────────────

    @Test
    void answerQuestion_endToEnd() {
        stubEmbedder();
        var meta = Map.<String, Object>of(
            "source_key", "policy.txt", "chunk_index", 0, "text", "Banking policy content");
        stubVectorStore(List.of(meta));
        when(generator.generate(anyString(), anyString())).thenReturn("Here is the answer.");

        String result = pipeline(5).answerQuestion("What is banking policy?");

        assertEquals("Here is the answer.", result);
        verify(embedder).embedText("What is banking policy?");
        verify(generator).generate(anyString(), anyString());
    }

    @Test
    void answerQuestion_noContextStillGenerates() {
        stubEmbedder();
        stubVectorStore(List.of());
        when(generator.generate(anyString(), anyString())).thenReturn("I don't have enough context.");

        String result = pipeline(5).answerQuestion("Unknown question?");

        assertNotNull(result);
        verify(generator).generate(anyString(), anyString());
    }

    @Test
    void answerQuestion_passesTopKToVectorStore() {
        stubEmbedder();
        stubVectorStore(List.of());
        when(generator.generate(anyString(), anyString())).thenReturn("ok");

        pipeline(7).answerQuestion("question?", 7, VECTOR_BUCKET);

        ArgumentCaptor<QueryVectorsRequest> cap = ArgumentCaptor.forClass(QueryVectorsRequest.class);
        verify(s3Vectors).queryVectors(cap.capture());
        assertEquals(7, cap.getValue().topK());
    }

    @Test
    void answerQuestion_passesSystemPromptToGenerator() {
        stubEmbedder();
        stubVectorStore(List.of());
        when(generator.generate(anyString(), anyString())).thenReturn("ok");

        pipeline(5).answerQuestion("question?");

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(generator).generate(sysCaptor.capture(), anyString());
        assertTrue(sysCaptor.getValue().contains("banking data platform"));
    }
}
