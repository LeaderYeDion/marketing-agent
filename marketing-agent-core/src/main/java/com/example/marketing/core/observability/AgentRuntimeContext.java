package com.example.marketing.core.observability;

import java.time.Instant;
import java.util.Map;

public record AgentRuntimeContext(
        String traceId,
        String requestId,
        String conversationId,
        String userId,
        Instant startedAt,
        Map<String, String> tags
) {
    public static AgentRuntimeContext create(String conversationId, String userId) {
        String uuid = java.util.UUID.randomUUID().toString();
        return new AgentRuntimeContext("trace_" + uuid, "req_" + uuid.substring(0, 8),
                conversationId == null ? "unknown" : conversationId,
                userId == null ? "" : userId,
                Instant.now(), Map.of());
    }
}
