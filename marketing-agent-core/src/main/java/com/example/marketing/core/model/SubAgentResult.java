package com.example.marketing.core.model;

import java.util.List;
import java.util.Map;

public record SubAgentResult(
        String invocationId,
        String status,
        String userVisibleSummary,
        String mainContextSummary,
        Map<String, Object> statePatch,
        List<VisibleObject> visibleObjects,
        List<ConversationMessage> messagesToCommit,
        Map<String, Object> failure
) {
    public static SubAgentResult failed(String invocationId, String userVisibleSummary, String mainContextSummary,
                                        Map<String, Object> failure, List<ConversationMessage> messagesToCommit) {
        return new SubAgentResult(invocationId, "failed", userVisibleSummary, mainContextSummary, Map.of(),
                List.of(), messagesToCommit == null ? List.of() : List.copyOf(messagesToCommit),
                failure == null ? Map.of() : Map.copyOf(failure));
    }
}
