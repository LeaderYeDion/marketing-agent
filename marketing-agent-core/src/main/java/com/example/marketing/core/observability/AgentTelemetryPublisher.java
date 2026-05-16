package com.example.marketing.core.observability;

public interface AgentTelemetryPublisher {
    void publish(AgentTelemetryEvent event);
}
