package com.example.marketing.core.audit;

public interface AuditEventPublisher {
    void publish(AuditEvent event);
}
