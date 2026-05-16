package com.example.marketing.core.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LlmGateway {
    private final LlmClient legacyClient;
    private final List<LlmCallObserver> observers;
    private final String defaultProvider;
    private final String defaultModel;
    private final Duration defaultTimeout;
    private final int defaultRetries;
    private final List<String> defaultFallbackModels;
    private final SimpleRateLimiter rateLimiter;
    private final SimpleCircuitBreaker circuitBreaker;

    public LlmGateway(LlmClient legacyClient,
                      List<LlmCallObserver> observers,
                      @Value("${agent.llm.default-provider:gemini}") String defaultProvider,
                      @Value("${agent.llm.default-model:gemini-2.5-flash-lite}") String defaultModel,
                      @Value("${agent.llm.timeout-seconds:60}") long timeoutSeconds,
                      @Value("${agent.llm.retry.max-attempts:1}") int defaultRetries,
                      @Value("${agent.llm.fallback-models:}") String fallbackModels,
                      @Value("${agent.llm.rate-limit.per-minute:60}") int requestsPerMinute,
                      @Value("${agent.llm.circuit-breaker.failure-threshold:5}") int failureThreshold,
                      @Value("${agent.llm.circuit-breaker.open-seconds:30}") long openSeconds) {
        this.legacyClient = legacyClient;
        this.observers = observers == null ? List.of() : List.copyOf(observers);
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.defaultTimeout = Duration.ofSeconds(timeoutSeconds);
        this.defaultRetries = defaultRetries;
        this.defaultFallbackModels = splitCsv(fallbackModels);
        this.rateLimiter = new SimpleRateLimiter(Math.max(1, requestsPerMinute));
        this.circuitBreaker = new SimpleCircuitBreaker(Math.max(1, failureThreshold), Duration.ofSeconds(openSeconds));
    }

    public String generateText(LlmRequest request) {
        return generate(request).text();
    }

    public LlmResponse generate(LlmRequest request) {
        LlmRequest normalized = request.withRuntimeDefaults(defaultProvider, defaultModel, defaultTimeout,
                defaultRetries, defaultFallbackModels);
        if (!rateLimiter.tryAcquire()) {
            throw new LlmRuntimeException(LlmErrorType.RATE_LIMITED, "LLM rate limit exceeded", true);
        }
        if (!circuitBreaker.allowRequest()) {
            throw new LlmRuntimeException(LlmErrorType.CIRCUIT_OPEN, "LLM circuit breaker is open", true);
        }
        observers.forEach(observer -> observer.onStart(normalized));
        return callWithRetryAndFallback(normalized);
    }

    private LlmResponse callWithRetryAndFallback(LlmRequest request) {
        List<String> models = request.fallbackModels().isEmpty()
                ? List.of(request.model())
                : concat(request.model(), request.fallbackModels());
        RuntimeException last = null;
        int retryCount = 0;
        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            LlmRequest modelRequest = new LlmRequest(request.purpose(), request.provider(), models.get(modelIndex),
                    request.systemMessage(), request.messages(), request.temperature(), request.timeout(),
                    request.maxRetries(), request.maxTokens(), request.conversationId(), request.invocationId(),
                    request.promptVersion(), request.fallbackModels(), request.tags());
            for (int attempt = 0; attempt <= request.maxRetries(); attempt++) {
                Instant start = Instant.now();
                try {
                    String text = legacyClient.generate(modelRequest.systemMessage(), modelRequest.messages());
                    LlmResponse response = LlmResponse.success(text, modelRequest, Duration.between(start, Instant.now()),
                            retryCount, modelIndex > 0, estimateCost(text));
                    circuitBreaker.recordSuccess();
                    observers.forEach(observer -> observer.onSuccess(modelRequest, response));
                    return response;
                }
                catch (RuntimeException ex) {
                    retryCount++;
                    LlmErrorType errorType = classify(ex);
                    last = ex;
                    int observedRetryCount = retryCount;
                    observers.forEach(observer -> observer.onFailure(modelRequest, errorType, ex, observedRetryCount));
                    if (!isRetryable(errorType) || attempt >= request.maxRetries()) {
                        break;
                    }
                    sleepBriefly(attempt);
                }
            }
        }
        circuitBreaker.recordFailure();
        LlmErrorType type = classify(last);
        throw new LlmRuntimeException(type, last == null ? "LLM request failed" : last.getMessage(), isRetryable(type),
                last);
    }

    private LlmErrorType classify(RuntimeException ex) {
        if (ex instanceof LlmRuntimeException llmRuntimeException) {
            return llmRuntimeException.errorType();
        }
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return LlmErrorType.TIMEOUT;
        }
        if (message.contains("429") || message.contains("rate")) {
            return LlmErrorType.RATE_LIMITED;
        }
        if (message.contains("quota")) {
            return LlmErrorType.QUOTA_EXCEEDED;
        }
        if (message.contains("http 5")) {
            return LlmErrorType.PROVIDER_5XX;
        }
        if (message.contains("http 4")) {
            return LlmErrorType.PROVIDER_4XX;
        }
        if (message.contains("parse")) {
            return LlmErrorType.PARSE_FAILED;
        }
        return LlmErrorType.UNKNOWN;
    }

    private boolean isRetryable(LlmErrorType errorType) {
        return switch (errorType) {
            case TIMEOUT, RATE_LIMITED, PROVIDER_5XX, QUOTA_EXCEEDED, UNKNOWN -> true;
            default -> false;
        };
    }

    private void sleepBriefly(int attempt) {
        try {
            Thread.sleep(Math.min(1000L, 100L * (attempt + 1)));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmRuntimeException(LlmErrorType.UNKNOWN, "LLM retry interrupted", true, ex);
        }
    }

    private double estimateCost(String text) {
        int outputTokens = text == null ? 0 : Math.max(1, text.length() / 4);
        return outputTokens * 0.0000001;
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private List<String> concat(String first, List<String> rest) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(first), rest.stream()).distinct().toList();
    }

    private static final class SimpleRateLimiter {
        private final int limitPerMinute;
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger counter = new AtomicInteger();

        private SimpleRateLimiter(int limitPerMinute) {
            this.limitPerMinute = limitPerMinute;
        }

        private boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start >= 60_000L && windowStart.compareAndSet(start, now)) {
                counter.set(0);
            }
            return counter.incrementAndGet() <= limitPerMinute;
        }
    }

    private static final class SimpleCircuitBreaker {
        private final int failureThreshold;
        private final Duration openDuration;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntil = new AtomicLong(0);

        private SimpleCircuitBreaker(int failureThreshold, Duration openDuration) {
            this.failureThreshold = failureThreshold;
            this.openDuration = openDuration;
        }

        private boolean allowRequest() {
            return System.currentTimeMillis() >= openUntil.get();
        }

        private void recordSuccess() {
            failures.set(0);
            openUntil.set(0);
        }

        private void recordFailure() {
            if (failures.incrementAndGet() >= failureThreshold) {
                openUntil.set(System.currentTimeMillis() + openDuration.toMillis());
            }
        }
    }
}
