package com.example.marketing.core.observation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.VisibleObject;

public record Observation(
        String id,
        String runId,
        String taskNodeId,
        String workerName,
        String status,
        String summary,
        Map<String, Object> evidence,
        Map<String, Object> artifacts,
        double confidence,
        List<String> missingInputs,
        String riskLevel,
        boolean requiresApproval,
        ActionProposal actionProposal,
        String errorType,
        boolean retryable,
        List<VisibleObject> visibleObjects,
        List<ConversationMessage> messagesToCommit,
        Map<String, Object> statePatch
) {
    public Observation {
        id = id == null || id.isBlank() ? "obs_" + UUID.randomUUID().toString().substring(0, 8) : id;
        runId = runId == null ? "" : runId;
        taskNodeId = taskNodeId == null ? "" : taskNodeId;
        workerName = workerName == null ? "" : workerName;
        status = status == null ? "succeeded" : status;
        summary = summary == null ? "" : summary;
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        missingInputs = missingInputs == null ? List.of() : List.copyOf(missingInputs);
        riskLevel = riskLevel == null ? "medium" : riskLevel;
        errorType = errorType == null ? "" : errorType;
        visibleObjects = visibleObjects == null ? List.of() : List.copyOf(visibleObjects);
        messagesToCommit = messagesToCommit == null ? List.of() : List.copyOf(messagesToCommit);
        statePatch = statePatch == null ? Map.of() : Map.copyOf(statePatch);
    }

    public static Observation missingInputs(String runId, String taskNodeId, String workerName,
                                            List<String> missingInputs) {
        return new Observation(null, runId, taskNodeId, workerName, "waiting_for_user",
                "Need more information before this worker can run.", Map.of(), Map.of(), 0.2,
                missingInputs, "low", false, null, "MISSING_INPUTS", false, List.of(), List.of(), Map.of());
    }

    public static Observation failed(String runId, String taskNodeId, String workerName, String summary,
                                     String errorType, boolean retryable) {
        return new Observation(null, runId, taskNodeId, workerName, "failed", summary, Map.of(), Map.of(),
                0.0, List.of(), "medium", false, null, errorType, retryable, List.of(), List.of(), Map.of());
    }
}

