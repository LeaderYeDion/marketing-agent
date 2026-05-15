package com.example.marketing.core.operation;

import java.time.Instant;
import java.util.Map;

public record OperationRecord(
        String operationId,
        String idempotencyKey,
        String type,
        OperationStatus status,
        Map<String, Object> requestPayload,
        Map<String, Object> resultPayload,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static OperationRecord running(String operationId, String idempotencyKey, String type,
                                          Map<String, Object> requestPayload) {
        Instant now = Instant.now();
        return new OperationRecord(operationId, idempotencyKey, type, OperationStatus.RUNNING,
                requestPayload == null ? Map.of() : Map.copyOf(requestPayload), Map.of(), "", now, now);
    }

    public OperationRecord succeeded(Map<String, Object> resultPayload) {
        return new OperationRecord(operationId, idempotencyKey, type, OperationStatus.SUCCEEDED, requestPayload,
                resultPayload == null ? Map.of() : Map.copyOf(resultPayload), "", createdAt, Instant.now());
    }

    public OperationRecord failed(String errorMessage) {
        return new OperationRecord(operationId, idempotencyKey, type, OperationStatus.FAILED, requestPayload,
                resultPayload, errorMessage == null ? "" : errorMessage, createdAt, Instant.now());
    }
}
