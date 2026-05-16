package com.example.marketing.core.llm;

public enum LlmErrorType {
    NONE,
    TIMEOUT,
    RATE_LIMITED,
    CIRCUIT_OPEN,
    PROVIDER_4XX,
    PROVIDER_5XX,
    QUOTA_EXCEEDED,
    CONTENT_FILTERED,
    INVALID_RESPONSE,
    PARSE_FAILED,
    UNKNOWN
}
