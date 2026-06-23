package com.cloudpid.ai.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Pure utility methods for text chunking. No I/O. */
public final class TextChunker {

    private TextChunker() {}

    /**
     * Split text into overlapping fixed-size character windows.
     * Mirrors Python {@code chunk_text(text, chunk_size, overlap)}.
     */
    public static List<String> chunkText(String text, int chunkSize, int overlap) {
        if (text == null || text.isEmpty()) return List.of();
        int step = Math.max(1, chunkSize - overlap);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += step) {
            String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
            if (!chunk.isBlank()) chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * Deterministic vector key: SHA-1 hex of {@code "sourceKey#chunkIndex"}.
     * Mirrors Python {@code _chunk_key(source_key, chunk_index)}.
     */
    public static String chunkKey(String sourceKey, int chunkIndex) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest((sourceKey + "#" + chunkIndex).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
