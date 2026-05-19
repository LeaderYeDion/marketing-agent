package com.example.marketing.core.task;

import java.util.List;

public record TaskGraph(
        String id,
        String userGoal,
        List<TaskNode> nodes,
        String status
) {
    public TaskGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        status = status == null ? "planned" : status;
    }

    public TaskGraph withNode(TaskNode updatedNode) {
        List<TaskNode> updated = nodes.stream()
                .map(node -> node.id().equals(updatedNode.id()) ? updatedNode : node)
                .toList();
        return new TaskGraph(id, userGoal, updated, deriveStatus(updated));
    }

    public TaskGraph withStatus(String nextStatus) {
        return new TaskGraph(id, userGoal, nodes, nextStatus);
    }

    private String deriveStatus(List<TaskNode> currentNodes) {
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.WAITING_FOR_APPROVAL.equals(node.status()))) {
            return "waiting_for_approval";
        }
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.WAITING_FOR_USER.equals(node.status()))) {
            return "waiting_for_user";
        }
        if (currentNodes.stream().anyMatch(node -> TaskNodeStatus.FAILED.equals(node.status()))) {
            return "failed";
        }
        if (currentNodes.stream().allMatch(node -> TaskNodeStatus.SUCCEEDED.equals(node.status())
                || TaskNodeStatus.SKIPPED.equals(node.status()))) {
            return "succeeded";
        }
        return "running";
    }
}
