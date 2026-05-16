package com.example.marketing.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingAgentTelemetryPublisher implements AgentTelemetryPublisher {
    private static final Logger logger = LoggerFactory.getLogger(LoggingAgentTelemetryPublisher.class);

    @Override
    public void publish(AgentTelemetryEvent event) {
        logger.info("agent_event traceId={} requestId={} conversationId={} type={} source={} status={} latencyMs={} errorType={} data={}",
                event.traceId(), event.requestId(), event.conversationId(), event.eventType(), event.source(),
                event.status(), event.latencyMs(), event.errorType(), event.data());
    }
}
