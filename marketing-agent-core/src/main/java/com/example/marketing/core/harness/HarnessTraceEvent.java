package com.example.marketing.core.harness;

import java.time.Instant;
import java.util.Map;

public record HarnessTraceEvent(
        String runId,
        String eventType,
        String source,
        String status,
        Map<String, Object> data,
        Instant createdAt
) {
    public HarnessTraceEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static HarnessTraceEvent of(String runId, String eventType, String source, String status,
                                       Map<String, Object> data) {
        return new HarnessTraceEvent(runId, eventType, source, status, data, Instant.now());
    }
}
