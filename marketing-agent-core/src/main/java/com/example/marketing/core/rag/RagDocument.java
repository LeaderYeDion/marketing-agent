package com.example.marketing.core.rag;

import java.util.Map;

public record RagDocument(
        String id,
        String title,
        String content,
        Map<String, Object> metadata
) {
}
