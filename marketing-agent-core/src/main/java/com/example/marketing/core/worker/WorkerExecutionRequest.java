package com.example.marketing.core.worker;

import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.VisibleObject;

public record WorkerExecutionRequest(
        String runId,
        String taskGraphId,
        String taskNodeId,
        String conversationId,
        String userInput,
        WorkerDescriptor worker,
        Map<String, Object> inputs,
        String compressedContext,
        Map<String, Object> workspaceRefs,
        List<VisibleObject> visibleObjects
) {
    public WorkerExecutionRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        compressedContext = compressedContext == null ? "" : compressedContext;
        workspaceRefs = workspaceRefs == null ? Map.of() : Map.copyOf(workspaceRefs);
        visibleObjects = visibleObjects == null ? List.of() : List.copyOf(visibleObjects);
    }
}

