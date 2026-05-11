package com.example.marketing.core.model;

import java.util.List;
import java.util.Map;

public record SubAgentInvocation(
        String invocationId,
        String conversationId,
        String sourceMessageId,
        String skillName,
        String task,
        Map<String, Object> inputs,
        String compressedContext,
        List<VisibleObject> visibleObjects,
        Map<String, Object> outputContract
) {
}
