package com.example.marketing.core.memory;

import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.workspace.WorkspaceEntry;

public record HarnessMemory(
        List<ConversationMessage> recentMessages,
        List<ContextSummary> handoffSummaries,
        Map<String, VisibleObject> visibleObjects,
        Map<String, PendingAction> pendingActions,
        Map<String, Object> workingState,
        Map<String, Object> taskMemory,
        Map<String, Object> artifactMemory,
        Map<String, Object> decisionMemory,
        Map<String, Object> workspaceRefs,
        List<WorkspaceEntry> workspaceEntries
) {
    public HarnessMemory {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        handoffSummaries = handoffSummaries == null ? List.of() : List.copyOf(handoffSummaries);
        visibleObjects = visibleObjects == null ? Map.of() : Map.copyOf(visibleObjects);
        pendingActions = pendingActions == null ? Map.of() : Map.copyOf(pendingActions);
        workingState = workingState == null ? Map.of() : Map.copyOf(workingState);
        taskMemory = taskMemory == null ? Map.of() : Map.copyOf(taskMemory);
        artifactMemory = artifactMemory == null ? Map.of() : Map.copyOf(artifactMemory);
        decisionMemory = decisionMemory == null ? Map.of() : Map.copyOf(decisionMemory);
        workspaceRefs = workspaceRefs == null ? Map.of() : Map.copyOf(workspaceRefs);
        workspaceEntries = workspaceEntries == null ? List.of() : List.copyOf(workspaceEntries);
    }
}
