package com.example.marketing.core.model;

import java.time.Instant;
import java.util.Map;

public record ContextSummary(
        String source,
        String invocationId,
        String status,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static ContextSummary of(String source, String invocationId, String status, String content,
                                    Map<String, Object> metadata) {
        return new ContextSummary(source, invocationId, status, content,
                metadata == null ? Map.of() : Map.copyOf(metadata), Instant.now());
    }
}
