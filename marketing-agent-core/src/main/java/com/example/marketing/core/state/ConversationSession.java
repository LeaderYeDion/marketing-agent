package com.example.marketing.core.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.PendingActionStatus;
import com.example.marketing.core.model.VisibleObject;

public class ConversationSession {
    private final String conversationId;
    private final List<ConversationMessage> messages = new ArrayList<>();
    private final List<ContextSummary> handoffSummaries = new ArrayList<>();
    private final Map<String, VisibleObject> visibleObjects = new LinkedHashMap<>();
    private final Map<String, PendingAction> pendingActions = new LinkedHashMap<>();
    private final Map<String, Object> state = new LinkedHashMap<>();

    public ConversationSession(String conversationId) {
        this.conversationId = conversationId;
    }

    public static ConversationSession fromSnapshot(ConversationSnapshot snapshot) {
        ConversationSession session = new ConversationSession(snapshot.conversationId());
        if (snapshot.messages() != null) {
            session.messages.addAll(snapshot.messages());
        }
        if (snapshot.handoffSummaries() != null) {
            session.handoffSummaries.addAll(snapshot.handoffSummaries());
        }
        if (snapshot.visibleObjects() != null) {
            session.visibleObjects.putAll(snapshot.visibleObjects());
        }
        if (snapshot.pendingActions() != null) {
            session.pendingActions.putAll(snapshot.pendingActions());
        }
        if (snapshot.state() != null) {
            session.state.putAll(snapshot.state());
        }
        return session;
    }

    public ConversationSnapshot toSnapshot() {
        return new ConversationSnapshot(conversationId, List.copyOf(messages), List.copyOf(handoffSummaries),
                Map.copyOf(visibleObjects), Map.copyOf(pendingActions), Map.copyOf(state));
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

    public Map<String, PendingAction> pendingActions() {
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

    public void updateVisibleObjectStatus(String id, String status) {
        VisibleObject object = visibleObjects.get(id);
        if (object != null) {
            visibleObjects.put(id, new VisibleObject(object.id(), object.type(), object.title(), status,
                    object.summary(), object.data(), object.createdAt()));
        }
    }

    public void addPendingAction(PendingAction action) {
        pendingActions.put(action.id(), action);
    }

    public java.util.Optional<PendingAction> findPendingAction(String pendingActionId, String visibleObjectId) {
        if (pendingActionId != null && !pendingActionId.isBlank()) {
            PendingAction action = pendingActions.get(pendingActionId);
            return action == null ? java.util.Optional.empty() : java.util.Optional.of(action);
        }
        if (visibleObjectId != null && !visibleObjectId.isBlank()) {
            return pendingActions.values().stream()
                    .filter(action -> visibleObjectId.equals(action.visibleObjectId()))
                    .findFirst();
        }
        return java.util.Optional.empty();
    }

    public void updatePendingAction(PendingAction action) {
        pendingActions.put(action.id(), action);
    }

    public List<String> activePendingActionIds() {
        return pendingActions.values().stream()
                .filter(action -> PendingActionStatus.PENDING.equals(action.status()))
                .map(PendingAction::id)
                .toList();
    }
}
