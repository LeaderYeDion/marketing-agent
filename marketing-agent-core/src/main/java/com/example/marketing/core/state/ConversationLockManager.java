package com.example.marketing.core.state;

public interface ConversationLockManager {
    <T> T withConversationLock(String conversationId, java.util.function.Supplier<T> supplier);
}
