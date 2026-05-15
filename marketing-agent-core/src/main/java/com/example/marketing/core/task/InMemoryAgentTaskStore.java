package com.example.marketing.core.task;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class InMemoryAgentTaskStore implements AgentTaskStore {
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();

    @Override
    public AgentTask create(String conversationId, String sourceAgent, Map<String, Object> input) {
        AgentTask task = AgentTask.created("task_" + UUID.randomUUID().toString().substring(0, 8), conversationId,
                sourceAgent, input);
        tasks.put(task.taskId(), task);
        return task;
    }

    @Override
    public Optional<AgentTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public void save(AgentTask task) {
        tasks.put(task.taskId(), task);
    }
}
