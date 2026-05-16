package com.example.marketing.core.observability;

import java.time.Instant;
import java.util.Map;

public record AgentTelemetryEvent(
        String traceId,
        String requestId,
        String conversationId,
        String eventType,
        String source,
        String status,
        long latencyMs,
        String errorType,
        Map<String, Object> data,
        Instant timestamp
) {
    public static AgentTelemetryEvent of(String eventType, String source, String status, long latencyMs,
                                         String errorType, Map<String, Object> data) {
        AgentRuntimeContext context = AgentRuntimeContextHolder.current()
                .orElse(new AgentRuntimeContext("", "", "", "", Instant.now(), Map.of()));
        return new AgentTelemetryEvent(context.traceId(), context.requestId(), context.conversationId(),
                eventType, source == null ? "" : source, status == null ? "" : status, latencyMs,
                errorType == null ? "" : errorType, data == null ? Map.of() : Map.copyOf(data), Instant.now());
    }
}
