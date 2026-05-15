package com.example.marketing.core.operation;

import java.util.Optional;

public interface OperationStore {
    Optional<OperationRecord> findByIdempotencyKey(String idempotencyKey);

    OperationRecord start(String operationId, String idempotencyKey, String type,
                          java.util.Map<String, Object> requestPayload);

    void save(OperationRecord record);
}
