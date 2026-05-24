package com.example.marketing.eval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SystemTrace(
        Map<String, Object> plannerOutput,
        Map<String, Object> taskGraph,
        List<String> workerSequence,
        List<Map<String, Object>> observations,
        List<Map<String, Object>> evidence,
        List<Map<String, Object>> artifacts,
        Map<String, Object> workspaceRefs,
        List<Map<String, Object>> workspaceEntries,
        List<Map<String, Object>> ragRetrievedChunks,
        List<String> pendingActions,
        List<Map<String, Object>> recoveryDecisions,
        List<Map<String, Object>> telemetryAuditTrace,
        List<Map<String, Object>> observationEvaluations,
        List<Map<String, Object>> riskAssessments,
        List<Map<String, Object>> workers,
        Map<String, Object> harness
) {
    public SystemTrace {
        plannerOutput = immutable(plannerOutput);
        taskGraph = immutable(taskGraph);
        workerSequence = workerSequence == null ? List.of() : List.copyOf(workerSequence);
        observations = observations == null ? List.of() : List.copyOf(observations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        workspaceRefs = immutable(workspaceRefs);
        workspaceEntries = workspaceEntries == null ? List.of() : List.copyOf(workspaceEntries);
        ragRetrievedChunks = ragRetrievedChunks == null ? List.of() : List.copyOf(ragRetrievedChunks);
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
        recoveryDecisions = recoveryDecisions == null ? List.of() : List.copyOf(recoveryDecisions);
        telemetryAuditTrace = telemetryAuditTrace == null ? List.of() : List.copyOf(telemetryAuditTrace);
        observationEvaluations = observationEvaluations == null ? List.of() : List.copyOf(observationEvaluations);
        riskAssessments = riskAssessments == null ? List.of() : List.copyOf(riskAssessments);
        workers = workers == null ? List.of() : List.copyOf(workers);
        harness = immutable(harness);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
