package com.example.marketing.api;

import java.util.List;
import java.util.Map;

public record MarketingResponse(
        String conversationId,
        String answer,
        List<String> suggestions,
        List<String> retrievedDocuments,
        Map<String, Object> metadata
) {
}
