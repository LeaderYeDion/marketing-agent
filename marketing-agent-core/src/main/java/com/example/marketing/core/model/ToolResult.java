package com.example.marketing.core.model;

import java.util.Map;

public record ToolResult(
        boolean ok,
        String toolName,
        String errorCode,
        String userSafeMessage,
        String technicalMessage,
        boolean retryable,
        Map<String, Object> data
) {
    public static ToolResult ok(String toolName, Map<String, Object> data) {
        return new ToolResult(true, toolName, null, null, null, false, data == null ? Map.of() : Map.copyOf(data));
    }

    public static ToolResult failed(String toolName, String errorCode, String userSafeMessage,
                                    String technicalMessage, boolean retryable) {
        return new ToolResult(false, toolName, errorCode, userSafeMessage, technicalMessage, retryable, Map.of());
    }
}
