package com.example.marketing.core.llm;

import java.time.Duration;
import java.util.Map;

public record LlmResponse(
        String text,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        double estimatedCost,
        Duration latency,
        String finishReason,
        LlmErrorType errorType,
        String rawErrorCode,
        int retryCount,
        boolean degraded,
        Map<String, Object> metadata
) {
    public static LlmResponse success(String text, LlmRequest request, Duration latency, int retryCount,
                                      boolean degraded, double estimatedCost) {
        return new LlmResponse(text == null ? "" : text, request.provider(), request.model(),
                estimateTokens(request.systemMessage()), estimateTokens(text), estimatedCost, latency, "stop",
                LlmErrorType.NONE, "", retryCount, degraded, Map.of());
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }
}
