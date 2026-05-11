package com.example.marketing.core.model;

import java.time.Instant;
import java.util.Map;

public record ConversationMessage(
        MessageRole role,
        String content,
        String source,
        String eventType,
        boolean visible,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static ConversationMessage user(String content, Map<String, Object> metadata) {
        return new ConversationMessage(MessageRole.USER, content, "user", "user_text", true,
                metadata == null ? Map.of() : Map.copyOf(metadata), Instant.now());
    }

    public static ConversationMessage assistant(String content, String source, String eventType,
                                                Map<String, Object> metadata) {
        return new ConversationMessage(MessageRole.ASSISTANT, content, source, eventType, true,
                metadata == null ? Map.of() : Map.copyOf(metadata), Instant.now());
    }
}
