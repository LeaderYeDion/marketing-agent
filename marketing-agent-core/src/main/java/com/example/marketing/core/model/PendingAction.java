package com.example.marketing.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record PendingAction(
        String id,
        String type,
        String sourceAgent,
        String invocationId,
        String visibleObjectId,
        PendingActionStatus status,
        Map<String, Object> payload,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        long version
) {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    public static PendingAction create(String id, String type, String sourceAgent, String invocationId,
                                       String visibleObjectId, Map<String, Object> payload) {
        Instant now = Instant.now();
        return new PendingAction(id, type, sourceAgent, invocationId, visibleObjectId, PendingActionStatus.PENDING,
                payload == null ? Map.of() : Map.copyOf(payload), now, now.plus(DEFAULT_TTL), null, null, 1);
    }

    public boolean isPending() {
        return PendingActionStatus.PENDING.equals(status);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public PendingAction withStatus(PendingActionStatus nextStatus, String decidedBy) {
        return new PendingAction(id, type, sourceAgent, invocationId, visibleObjectId, nextStatus, payload, createdAt,
                expiresAt, Instant.now(), decidedBy, version + 1);
    }

    public PendingAction withEditedPayload(Map<String, Object> editedPayload, String decidedBy) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(payload);
        if (editedPayload != null) {
            merged.putAll(editedPayload);
        }
        return new PendingAction(id, type, sourceAgent, invocationId, visibleObjectId, PendingActionStatus.EDITED,
                Map.copyOf(merged), createdAt, expiresAt, Instant.now(), decidedBy, version + 1);
    }
}
