package com.example.marketing.api;

import java.util.List;
import java.util.Map;

public record MarketingRequest(
        String conversationId,
        String userId,
        String query,
        String channel,
        String product,
        String audience,
        List<String> goals,
        Map<String, Object> variables
) {
}
