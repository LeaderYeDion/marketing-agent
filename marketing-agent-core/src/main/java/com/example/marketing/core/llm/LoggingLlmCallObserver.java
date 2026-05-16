package com.example.marketing.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingLlmCallObserver implements LlmCallObserver {
    private static final Logger logger = LoggerFactory.getLogger(LoggingLlmCallObserver.class);

    @Override
    public void onSuccess(LlmRequest request, LlmResponse response) {
        logger.info("llm_call purpose={} provider={} model={} latencyMs={} retries={} degraded={} inputTokens={} outputTokens={} estimatedCost={}",
                request.purpose(), response.provider(), response.model(), response.latency().toMillis(),
                response.retryCount(), response.degraded(), response.inputTokens(), response.outputTokens(),
                response.estimatedCost());
    }

    @Override
    public void onFailure(LlmRequest request, LlmErrorType errorType, Throwable error, int retryCount) {
        logger.warn("llm_call_failed purpose={} provider={} model={} errorType={} retries={} message={}",
                request.purpose(), request.provider(), request.model(), errorType, retryCount, error.getMessage());
    }
}
