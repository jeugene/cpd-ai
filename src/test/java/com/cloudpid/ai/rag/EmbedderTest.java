package com.cloudpid.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbedderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "amazon.titan-embed-text-v2:0";
    private static final int DIMS = 1024;

    @Mock
    BedrockRuntimeClient bedrockClient;

    private Embedder embedder() {
        return new Embedder(bedrockClient, MODEL, DIMS);
    }

    private void stubEmbedding(List<Float> vector) throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("embedding", vector));
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
            .thenReturn(InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(json))
                .build());
    }

    @Test
    void embedText_returnsFloatList() throws Exception {
        List<Float> expected = Collections.nCopies(DIMS, 0.5f);
        stubEmbedding(expected);

        List<Float> result = embedder().embedText("hello banking world");

        assertEquals(DIMS, result.size());
        assertEquals(0.5f, result.get(0), 1e-6f);
    }

    @Test
    void embedText_callsCorrectModelId() throws Exception {
        stubEmbedding(Collections.nCopies(DIMS, 0.1f));
        embedder().embedText("test");

        ArgumentCaptor<InvokeModelRequest> cap = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(cap.capture());
        assertEquals(MODEL, cap.getValue().modelId());
    }

    @Test
    void embedText_sendsCorrectBody() throws Exception {
        stubEmbedding(Collections.nCopies(DIMS, 0.1f));
        embedder().embedText("my text");

        ArgumentCaptor<InvokeModelRequest> cap = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(cap.capture());
        String body = cap.getValue().body().asUtf8String();
        assertTrue(body.contains("\"inputText\""));
        assertTrue(body.contains("\"my text\""));
        assertTrue(body.contains("\"dimensions\""));
        assertTrue(body.contains(String.valueOf(DIMS)));
    }

    @Test
    void embedText_setsContentTypeAndAccept() throws Exception {
        stubEmbedding(Collections.nCopies(DIMS, 0.1f));
        embedder().embedText("test");

        ArgumentCaptor<InvokeModelRequest> cap = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(cap.capture());
        assertEquals("application/json", cap.getValue().contentType());
        assertEquals("application/json", cap.getValue().accept());
    }

    @Test
    void embedText_throwsOnMissingEmbeddingField() throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("other_field", "unexpected"));
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
            .thenReturn(InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(json))
                .build());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> embedder().embedText("test"));
        assertTrue(ex.getMessage().contains("Unexpected Bedrock embedding response"));
    }

    @Test
    void embedText_throwsOnNonArrayEmbeddingField() throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("embedding", "not-an-array"));
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
            .thenReturn(InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(json))
                .build());

        assertThrows(IllegalStateException.class, () -> embedder().embedText("test"));
    }
}
