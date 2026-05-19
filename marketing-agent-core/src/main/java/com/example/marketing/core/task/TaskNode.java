package com.example.marketing.core.task;

import java.util.List;
import java.util.Map;

public record TaskNode(
        String id,
        String goal,
        String capabilityName,
        Map<String, Object> inputs,
        List<String> dependsOn,
        TaskNodeStatus status,
        String riskLevel,
        String observationId
) {
    public TaskNode {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        status = status == null ? TaskNodeStatus.PENDING : status;
        riskLevel = riskLevel == null ? "medium" : riskLevel;
        observationId = observationId == null ? "" : observationId;
    }

    public static TaskNode pending(String id, String goal, String capabilityName, Map<String, Object> inputs,
                                   List<String> dependsOn) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, TaskNodeStatus.PENDING, "medium", "");
    }

    public TaskNode withStatus(TaskNodeStatus nextStatus) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, nextStatus, riskLevel, observationId);
    }

    public TaskNode withRisk(String nextRiskLevel) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, status, nextRiskLevel, observationId);
    }

    public TaskNode withObservation(TaskNodeStatus nextStatus, String nextObservationId) {
        return new TaskNode(id, goal, capabilityName, inputs, dependsOn, nextStatus, riskLevel, nextObservationId);
    }

    public TaskNode withInputs(Map<String, Object> nextInputs) {
        return new TaskNode(id, goal, capabilityName, nextInputs, dependsOn, status, riskLevel, observationId);
    }
}
