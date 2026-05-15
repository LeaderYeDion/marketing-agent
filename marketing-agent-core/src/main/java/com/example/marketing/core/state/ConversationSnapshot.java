package com.example.marketing.core.state;

import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.VisibleObject;

public record ConversationSnapshot(
        String conversationId,
        List<ConversationMessage> messages,
        List<ContextSummary> handoffSummaries,
        Map<String, VisibleObject> visibleObjects,
        Map<String, PendingAction> pendingActions,
        Map<String, Object> state
) {
}
