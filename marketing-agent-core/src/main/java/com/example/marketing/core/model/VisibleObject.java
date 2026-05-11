package com.example.marketing.core.model;

import java.time.Instant;
import java.util.Map;

public record VisibleObject(
        String id,
        String type,
        String title,
        String status,
        String summary,
        Map<String, Object> data,
        Instant createdAt
) {
    public static VisibleObject of(String id, String type, String title, String status, String summary,
                                   Map<String, Object> data) {
        return new VisibleObject(id, type, title, status, summary, data == null ? Map.of() : Map.copyOf(data),
                Instant.now());
    }
}
