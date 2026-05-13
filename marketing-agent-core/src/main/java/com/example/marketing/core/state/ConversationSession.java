package com.example.marketing.core.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.VisibleObject;

public class ConversationSession {
    private final String conversationId;
    private final List<ConversationMessage> messages = new ArrayList<>();
    private final List<ContextSummary> handoffSummaries = new ArrayList<>();
    private final Map<String, VisibleObject> visibleObjects = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> pendingActions = new LinkedHashMap<>();
    private final Map<String, Object> state = new LinkedHashMap<>();

    public ConversationSession(String conversationId) {
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return conversationId;
    }

    public List<ConversationMessage> messages() {
        return messages;
    }

    public List<ContextSummary> handoffSummaries() {
        return handoffSummaries;
    }

    public Map<String, VisibleObject> visibleObjects() {
        return visibleObjects;
    }

    public Map<String, Map<String, Object>> pendingActions() {
        return pendingActions;
    }

    public Map<String, Object> state() {
        return state;
    }

    public void addMessage(ConversationMessage message) {
        messages.add(message);
    }

    public void addHandoffSummary(ContextSummary summary) {
        handoffSummaries.add(summary);
    }

    public void addVisibleObject(VisibleObject visibleObject) {
        visibleObjects.put(visibleObject.id(), visibleObject);
    }

    public void addPendingAction(String id, Map<String, Object> action) {
        pendingActions.put(id, new LinkedHashMap<>(action));
    }
}
