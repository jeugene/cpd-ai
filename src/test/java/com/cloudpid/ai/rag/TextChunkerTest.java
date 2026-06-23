package com.cloudpid.ai.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    // ── chunkText ──────────────────────────────────────────────────────────────

    @Test
    void chunkText_basicSplit() {
        List<String> chunks = TextChunker.chunkText("a".repeat(1000), 100, 0);
        assertTrue(chunks.stream().allMatch(c -> c.length() == 100));
        assertEquals(10, chunks.size());
    }

    @Test
    void chunkText_overlap() {
        List<String> chunks = TextChunker.chunkText("abcdefghij", 5, 2);
        // step = 3; starts at 0, 3, 6, 9 → "abcde", "defgh", "ghij", "j"
        assertEquals("abcde", chunks.get(0));
        assertEquals("defgh", chunks.get(1));
        assertEquals("ghij", chunks.get(2));
        assertEquals(4, chunks.size());
    }

    @Test
    void chunkText_emptyReturnsEmpty() {
        assertEquals(List.of(), TextChunker.chunkText("", 100, 10));
    }

    @Test
    void chunkText_nullReturnsEmpty() {
        assertEquals(List.of(), TextChunker.chunkText(null, 100, 10));
    }

    @Test
    void chunkText_shorterThanChunkSize() {
        List<String> chunks = TextChunker.chunkText("short text", 500, 50);
        assertEquals(1, chunks.size());
        assertEquals("short text", chunks.get(0));
    }

    @Test
    void chunkText_stripsWhitespaceOnlyWindows() {
        String text = "hello" + " ".repeat(500) + "world";
        List<String> chunks = TextChunker.chunkText(text, 50, 0);
        assertTrue(chunks.stream().allMatch(c -> !c.isBlank()));
    }

    // ── chunkKey ──────────────────────────────────────────────────────────────

    @Test
    void chunkKey_isDeterministic() {
        assertEquals(
            TextChunker.chunkKey("docs/file.txt", 0),
            TextChunker.chunkKey("docs/file.txt", 0)
        );
    }

    @Test
    void chunkKey_differsByIndex() {
        assertNotEquals(
            TextChunker.chunkKey("doc.txt", 0),
            TextChunker.chunkKey("doc.txt", 1)
        );
    }

    @Test
    void chunkKey_differsBySource() {
        assertNotEquals(
            TextChunker.chunkKey("a.txt", 0),
            TextChunker.chunkKey("b.txt", 0)
        );
    }

    @Test
    void chunkKey_returnsSha1Hex() {
        String key = TextChunker.chunkKey("doc.txt", 0);
        assertEquals(40, key.length());
        assertTrue(key.matches("[0-9a-f]{40}"));
    }
}
