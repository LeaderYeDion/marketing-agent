package com.example.marketing.api;

import java.time.Instant;
import java.util.Map;

public record MarketingStreamEvent(
        String conversationId,
        String type,
        String node,
        String message,
        Map<String, Object> data,
        Instant timestamp
) {
    public static MarketingStreamEvent of(String conversationId, String type, String node, String message,
                                          Map<String, Object> data) {
        return new MarketingStreamEvent(conversationId, type, node, message, data, Instant.now());
    }
}
