package com.example.marketing.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoggingAuditEventPublisher implements AuditEventPublisher {
    private static final Logger logger = LoggerFactory.getLogger(LoggingAuditEventPublisher.class);

    @Override
    public void publish(AuditEvent event) {
        logger.info("audit_event type={} conversationId={} source={} data={}", event.type(), event.conversationId(),
                event.source(), event.data());
    }
}
