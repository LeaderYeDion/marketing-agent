package com.example.marketing.core.llm;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.marketing.core.observability.AgentTelemetry;

@Component
public class TelemetryLlmCallObserver implements LlmCallObserver {
    private final AgentTelemetry telemetry;

    public TelemetryLlmCallObserver(AgentTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void onSuccess(LlmRequest request, LlmResponse response) {
        telemetry.event("llm_call", request.purpose(), "succeeded", Map.of(
                "provider", response.provider(),
                "model", response.model(),
                "latencyMs", response.latency().toMillis(),
                "retryCount", response.retryCount(),
                "degraded", response.degraded(),
                "inputTokens", response.inputTokens(),
                "outputTokens", response.outputTokens(),
                "estimatedCost", response.estimatedCost()
        ));
    }

    @Override
    public void onFailure(LlmRequest request, LlmErrorType errorType, Throwable error, int retryCount) {
        telemetry.event("llm_call", request.purpose(), "failed", Map.of(
                "provider", request.provider(),
                "model", request.model(),
                "errorType", errorType.name(),
                "retryCount", retryCount
        ));
    }
}
