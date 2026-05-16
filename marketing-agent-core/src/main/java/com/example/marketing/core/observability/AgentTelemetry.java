package com.example.marketing.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

@Service
public class AgentTelemetry {
    private final List<AgentTelemetryPublisher> publishers;

    public AgentTelemetry(List<AgentTelemetryPublisher> publishers) {
        this.publishers = publishers == null ? List.of() : List.copyOf(publishers);
    }

    public void event(String eventType, String source, String status, Map<String, Object> data) {
        publish(AgentTelemetryEvent.of(eventType, source, status, 0, "", data));
    }

    public <T> T timed(String eventType, String source, Supplier<T> supplier) {
        Instant start = Instant.now();
        try {
            T value = supplier.get();
            publish(AgentTelemetryEvent.of(eventType, source, "succeeded",
                    Duration.between(start, Instant.now()).toMillis(), "", Map.of()));
            return value;
        }
        catch (RuntimeException ex) {
            publish(AgentTelemetryEvent.of(eventType, source, "failed",
                    Duration.between(start, Instant.now()).toMillis(), ex.getClass().getSimpleName(),
                    Map.of("message", ex.getMessage() == null ? "" : ex.getMessage())));
            throw ex;
        }
    }

    private void publish(AgentTelemetryEvent event) {
        publishers.forEach(publisher -> publisher.publish(event));
    }
}
