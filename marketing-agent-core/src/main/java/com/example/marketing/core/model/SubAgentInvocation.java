package com.example.marketing.core.model;

import java.util.List;
import java.util.Map;

public record SubAgentInvocation(
        String invocationId,
        String conversationId,
        String sourceMessageId,
        String skillName,
        List<String> candidateSkills,
        String task,
        Map<String, Object> inputs,
        String compressedContext,
        List<VisibleObject> visibleObjects,
        Map<String, Object> outputContract
) {
    public SubAgentInvocation {
        candidateSkills = candidateSkills == null ? List.of() : List.copyOf(candidateSkills);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        visibleObjects = visibleObjects == null ? List.of() : List.copyOf(visibleObjects);
        outputContract = outputContract == null ? Map.of() : Map.copyOf(outputContract);
    }
}
