package com.example.marketing.core.rag;

import java.util.Map;

public record RagChunk(
        String documentId,
        String chunkId,
        String title,
        String content,
        String sourceUri,
        String version,
        double score,
        double confidence,
        String citationText,
        Map<String, Object> metadata
) {
    public RagDocument toDocument() {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("chunk_id", chunkId);
        data.put("source_uri", sourceUri);
        data.put("version", version);
        data.put("score", score);
        data.put("confidence", confidence);
        data.put("citation", citationText);
        if (metadata != null) {
            data.putAll(metadata);
        }
        return new RagDocument(documentId, title, content, data);
    }
}
