package com.example.marketing.core.task;

import java.util.Optional;

public interface AgentTaskStore {
    AgentTask create(String conversationId, String sourceAgent, java.util.Map<String, Object> input);

    Optional<AgentTask> find(String taskId);

    void save(AgentTask task);
}
