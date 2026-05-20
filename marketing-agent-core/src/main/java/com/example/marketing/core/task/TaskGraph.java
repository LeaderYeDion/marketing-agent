package com.example.marketing.core.task;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record TaskGraph(
        String id,
        String userGoal,
        List<TaskNode> nodes,
        String plannerRationale,
        String answerStrategy,
        String status
) {
    public TaskGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        plannerRationale = plannerRationale == null ? "" : plannerRationale;
        answerStrategy = answerStrategy == null ? "" : answerStrategy;
        status = status == null ? "planned" : status;
    }

    public TaskGraph(String id, String userGoal, List<TaskNode> nodes, String status) {
        this(id, userGoal, nodes, "", "", status);
    }

    public TaskGraph withNode(TaskNode updatedNode) {
        List<TaskNode> updated = nodes.stream()
                .map(node -> node.id().equals(updatedNode.id()) ? updatedNode : node)
                .toList();
        return new TaskGraph(id, userGoal, updated, plannerRationale, answerStrategy, deriveStatus(updated));
    }

    public TaskGraph withNodes(List<TaskNode> updatedNodes) {
        return new TaskGraph(id, userGoal, updatedNodes, plannerRationale, answerStrategy, deriveStatus(updatedNodes));
    }

    public TaskGraph withStatus(String nextStatus) {
        return new TaskGraph(id, userGoal, nodes, plannerRationale, answerStrategy, nextStatus);
    }

    public Optional<TaskNode> findNode(String nodeId) {
        return nodes.stream().filter(node -> node.id().equals(nodeId)).findFirst();
    }

    public List<TaskNode> readyNodes() {
        Set<String> completed = nodes.stream()
                .filter(node -> TaskNodeStatus.SUCCEEDED.equals(node.status())
                        || TaskNodeStatus.SKIPPED.equals(node.status()))
                .map(TaskNode::id)
                .collect(Collectors.toSet());
        return nodes.stream()
                .filter(node -> TaskNodeStatus.PENDING.equals(node.status()))
                .filter(node -> completed.containsAll(node.dependsOn()))
                .sorted(Comparator.comparingInt(TaskNode::priority))
                .toList();
    }

    public boolean hasPendingNodes() {
        return nodes.stream().anyMatch(node -> TaskNodeStatus.PENDING.equals(node.status()));
    }

    public boolean isPausedOrTerminal() {
        return "succeeded".equals(status)
                || "failed".equals(status)
                || "waiting_for_user".equals(status)
                || "waiting_for_approval".equals(status);
    }

    public TaskGraph appendNode(TaskNode node) {
        List<TaskNode> updated = new java.util.ArrayList<>(nodes);
        updated.add(node);
        return new TaskGraph(id, userGoal, updated, plannerRationale, answerStrategy, deriveStatus(updated));
    }

    public TaskGraph redirectDependents(String fromNodeId, String toNodeId) {
        List<TaskNode> updated = nodes.stream()
                .map(node -> {
                    if (node.id().equals(fromNodeId) || !node.dependsOn().contains(fromNodeId)) {
                        return node;
                    }
                    List<String> dependencies = node.dependsOn().stream()
                            .map(dependency -> dependency.equals(fromNodeId) ? toNodeId : dependency)
                            .distinct()
                            .toList();
                    return node.withDependsOn(dependencies);
                })
                .toList();
        return new TaskGraph(id, userGoal, updated, plannerRationale, answerStrategy, deriveStatus(updated));
    }

    public Map<String, TaskNodeStatus> nodeStatuses() {
        return nodes.stream().collect(Collectors.toMap(TaskNode::id, TaskNode::status));
    }

    private String deriveStatus(List<TaskNode> currentNodes) {
        if (currentNodes.isEmpty()) {
            return "empty";
        }
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.WAITING_FOR_APPROVAL.equals(node.status()))) {
            return "waiting_for_approval";
        }
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.WAITING_FOR_USER.equals(node.status()))) {
            return "waiting_for_user";
        }
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.FAILED.equals(node.status()))) {
            return "failed";
        }
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.RUNNING.equals(node.status())
                || TaskNodeStatus.PENDING.equals(node.status()))) {
            return "running";
        }
        if (currentNodes.stream().allMatch(node -> TaskNodeStatus.SUCCEEDED.equals(node.status())
                || TaskNodeStatus.SKIPPED.equals(node.status()))) {
            return "succeeded";
        }
        return "running";
    }
}
