package com.example.marketing.core.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.ConversationMessage;

public record LlmRequest(
        String purpose,
        String provider,
        String model,
        String systemMessage,
        List<ConversationMessage> messages,
        double temperature,
        Duration timeout,
        int maxRetries,
        int maxTokens,
        String conversationId,
        String invocationId,
        String promptVersion,
        List<String> fallbackModels,
        Map<String, String> tags
) {
    public static LlmRequest simple(String purpose, String systemMessage, List<ConversationMessage> messages) {
        return new LlmRequest(purpose, "", "", systemMessage, messages == null ? List.of() : List.copyOf(messages),
                0.2, Duration.ofSeconds(60), 1, 0, "", "", "", List.of(), Map.of());
    }

    public LlmRequest withRuntimeDefaults(String defaultProvider, String defaultModel, Duration defaultTimeout,
                                          int defaultRetries, List<String> defaultFallbackModels) {
        return new LlmRequest(
                blankToDefault(purpose, "default"),
                blankToDefault(provider, defaultProvider),
                blankToDefault(model, defaultModel),
                systemMessage == null ? "" : systemMessage,
                messages == null ? List.of() : List.copyOf(messages),
                temperature,
                timeout == null || timeout.isZero() ? defaultTimeout : timeout,
                maxRetries < 0 ? defaultRetries : maxRetries,
                maxTokens,
                conversationId == null ? "" : conversationId,
                invocationId == null ? "" : invocationId,
                promptVersion == null ? "" : promptVersion,
                fallbackModels == null || fallbackModels.isEmpty() ? defaultFallbackModels : List.copyOf(fallbackModels),
                tags == null ? Map.of() : Map.copyOf(tags)
        );
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
