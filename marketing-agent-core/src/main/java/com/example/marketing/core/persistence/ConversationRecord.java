package com.example.marketing.core.persistence;

import java.time.Instant;

import com.example.marketing.core.state.ConversationSnapshot;

public record ConversationRecord(
        String conversationId,
        ConversationSnapshot snapshot,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
