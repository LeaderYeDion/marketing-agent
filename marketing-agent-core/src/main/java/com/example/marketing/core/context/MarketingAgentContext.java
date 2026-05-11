package com.example.marketing.core.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.marketing.api.MarketingRequest;

public record MarketingAgentContext(
        String conversationId,
        String userId,
        String channel,
        String product,
        String audience,
        List<String> goals,
        Map<String, Object> variables,
        Instant createdAt
) {
    public static MarketingAgentContext from(MarketingRequest request) {
        return new MarketingAgentContext(
                request.conversationId(),
                request.userId(),
                defaultString(request.channel(), "unknown"),
                defaultString(request.product(), "未指定产品"),
                defaultString(request.audience(), "通用用户"),
                request.goals() == null ? List.of() : List.copyOf(request.goals()),
                request.variables() == null ? Map.of() : Map.copyOf(request.variables()),
                Instant.now()
        );
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
