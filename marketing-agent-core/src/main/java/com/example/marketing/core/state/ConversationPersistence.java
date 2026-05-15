package com.example.marketing.core.state;

import java.util.Optional;

public interface ConversationPersistence {
    Optional<ConversationSnapshot> load(String conversationId);

    void save(ConversationSnapshot snapshot);
}
