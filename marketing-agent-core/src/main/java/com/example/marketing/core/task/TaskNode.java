package com.example.marketing.core.task;

import java.util.List;
import java.util.Map;

public record TaskNode(
        String id,
        String goal,
        String capabilityName,
        Map<String, Object> inputs,
        List<String> dependsOn,
        String completionCriteria,
        int priority,
        String plannerRationale,
        int retryCount,
        TaskNodeStatus status,
        String riskLevel,
        String observationId
) {
    public TaskNode {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        completionCriteria = completionCriteria == null ? "" : completionCriteria;
        plannerRationale = plannerRationale == null ? "" : plannerRationale;
        status = status == null ? TaskNodeStatus.PENDING : status;
        riskLevel = riskLevel == null ? "medium" : riskLevel;
        observationId = observationId == null ? "" : observationId;
    }

    public static TaskNode pending(String id, String goal, String capabilityName, Map<String, Object> inputs,
                                   List<String> dependsOn) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, "", 100, "", 0,
                TaskNodeStatus.PENDING, "medium", "");
    }

    public static TaskNode planned(String id, String goal, String capabilityName, Map<String, Object> inputs,
                                   List<String> dependsOn, String completionCriteria, int priority,
                                   String plannerRationale) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, completionCriteria, priority,
                plannerRationale, 0, TaskNodeStatus.PENDING, "medium", "");
    }

    public TaskNode withStatus(TaskNodeStatus nextStatus) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, completionCriteria, priority,
                plannerRationale, retryCount, nextStatus, riskLevel, observationId);
    }

    public TaskNode withRisk(String nextRiskLevel) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, completionCriteria, priority,
                plannerRationale, retryCount, status, nextRiskLevel, observationId);
    }

    public TaskNode withObservation(TaskNodeStatus nextStatus, String nextObservationId) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, completionCriteria, priority,
                plannerRationale, retryCount, nextStatus, riskLevel, nextObservationId);
    }

    public TaskNode withInputs(Map<String, Object> nextInputs) {
        return new TaskNode(id, goal, capabilityName, nextInputs, dependsOn, completionCriteria, priority,
                plannerRationale, retryCount, status, riskLevel, observationId);
    }

    public TaskNode withDependsOn(List<String> nextDependsOn) {
        return new TaskNode(id, goal, capabilityName, inputs, nextDependsOn, completionCriteria, priority,
                plannerRationale, retryCount, status, riskLevel, observationId);
    }

    public TaskNode incrementRetry() {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, completionCriteria, priority,
                plannerRationale, retryCount + 1, status, riskLevel, observationId);
    }
}
