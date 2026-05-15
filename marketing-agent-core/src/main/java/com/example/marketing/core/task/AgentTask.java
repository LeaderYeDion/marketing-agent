package com.example.marketing.core.task;

import java.time.Instant;
import java.util.Map;

public record AgentTask(
        String taskId,
        String conversationId,
        String sourceAgent,
        AgentTaskStatus status,
        Map<String, Object> input,
        Map<String, Object> result,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentTask created(String taskId, String conversationId, String sourceAgent,
                                    Map<String, Object> input) {
        Instant now = Instant.now();
        return new AgentTask(taskId, conversationId, sourceAgent, AgentTaskStatus.CREATED,
                input == null ? Map.of() : Map.copyOf(input), Map.of(), "", now, now);
    }

    public AgentTask withStatus(AgentTaskStatus nextStatus, Map<String, Object> result, String errorMessage) {
        return new AgentTask(taskId, conversationId, sourceAgent, nextStatus, input,
                result == null ? Map.of() : Map.copyOf(result), errorMessage == null ? "" : errorMessage,
                createdAt, Instant.now());
    }
}
