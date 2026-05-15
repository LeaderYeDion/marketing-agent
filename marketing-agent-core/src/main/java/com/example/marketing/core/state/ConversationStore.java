package com.example.marketing.core.state;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

@Service
public class ConversationStore {
    private final ConcurrentMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final List<ConversationPersistence> persistences;

    public ConversationStore(List<ConversationPersistence> persistences) {
        this.persistences = persistences == null ? List.of() : List.copyOf(persistences);
    }

    public ConversationSession getOrCreate(String conversationId) {
        return sessions.computeIfAbsent(conversationId, this::loadOrCreate);
    }

    public void save(ConversationSession session) {
        sessions.put(session.conversationId(), session);
        ConversationSnapshot snapshot = session.toSnapshot();
        persistences.forEach(persistence -> persistence.save(snapshot));
    }

    private ConversationSession loadOrCreate(String conversationId) {
        return persistences.stream()
                .map(persistence -> persistence.load(conversationId))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .map(ConversationSession::fromSnapshot)
                .orElseGet(() -> new ConversationSession(conversationId));
    }
}
