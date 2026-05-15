package com.example.marketing.core.audit;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String type,
        String conversationId,
        String source,
        Map<String, Object> data,
        Instant createdAt
) {
    public static AuditEvent of(String type, String conversationId, String source, Map<String, Object> data) {
        return new AuditEvent(type, conversationId, source, data == null ? Map.of() : Map.copyOf(data),
                Instant.now());
    }
}
