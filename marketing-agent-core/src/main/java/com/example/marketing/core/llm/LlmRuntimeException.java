package com.example.marketing.core.llm;

public class LlmRuntimeException extends RuntimeException {
    private final LlmErrorType errorType;
    private final boolean retryable;

    public LlmRuntimeException(LlmErrorType errorType, String message, boolean retryable) {
        super(message);
        this.errorType = errorType;
        this.retryable = retryable;
    }

    public LlmRuntimeException(LlmErrorType errorType, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.retryable = retryable;
    }

    public LlmErrorType errorType() {
        return errorType;
    }

    public boolean retryable() {
        return retryable;
    }
}
