package com.example.marketing.core.capability;

import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.VisibleObject;

public record CapabilityExecutionRequest(
        String runId,
        String taskGraphId,
        String taskNodeId,
        String conversationId,
        String userInput,
        CapabilityDescriptor capability,
        Map<String, Object> inputs,
        String compressedContext,
        List<VisibleObject> visibleObjects
) {
    public CapabilityExecutionRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        compressedContext = compressedContext == null ? "" : compressedContext;
        visibleObjects = visibleObjects == null ? List.of() : List.copyOf(visibleObjects);
    }
}
