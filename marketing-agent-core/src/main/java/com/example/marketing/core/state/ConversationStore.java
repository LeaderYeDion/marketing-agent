package com.example.marketing.core.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

@Service
public class ConversationStore {
    private final ConcurrentMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    public ConversationSession getOrCreate(String conversationId) {
        return sessions.computeIfAbsent(conversationId, ConversationSession::new);
    }
}
