package com.example.marketing.core.llm;

public interface LlmCallObserver {
    default void onStart(LlmRequest request) {
    }

    default void onSuccess(LlmRequest request, LlmResponse response) {
    }

    default void onFailure(LlmRequest request, LlmErrorType errorType, Throwable error, int retryCount) {
    }
}
