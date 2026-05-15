package com.example.marketing.core.operation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class InMemoryOperationStore implements OperationStore {
    private final Map<String, OperationRecord> byIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public Optional<OperationRecord> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public OperationRecord start(String operationId, String idempotencyKey, String type,
                                 Map<String, Object> requestPayload) {
        OperationRecord record = OperationRecord.running(operationId, idempotencyKey, type, requestPayload);
        OperationRecord existing = byIdempotencyKey.putIfAbsent(idempotencyKey, record);
        return existing == null ? record : existing;
    }

    @Override
    public void save(OperationRecord record) {
        byIdempotencyKey.put(record.idempotencyKey(), record);
    }
}
