package com.example.marketing.core.persistence;

import java.time.Instant;
import java.util.Map;

public record OutboxEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        OutboxEventStatus status,
        Map<String, Object> payload,
        int publishAttempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static OutboxEvent newEvent(String eventId, String aggregateType, String aggregateId, String eventType,
                                       Map<String, Object> payload) {
        Instant now = Instant.now();
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, OutboxEventStatus.NEW,
                payload == null ? Map.of() : Map.copyOf(payload), 0, "", now, now);
    }
}
