package com.example.marketing.core.harness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.capability.CapabilityRegistry;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;

@Service
public class TaskGraphValidator {
    private final CapabilityRegistry capabilityRegistry;

    public TaskGraphValidator(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    public TaskGraphValidationResult validateAndRepair(TaskGraph graph) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> repairs = new ArrayList<>();
        if (graph == null) {
            return new TaskGraphValidationResult(null, List.of("TASK_GRAPH_NULL"), warnings, repairs);
        }
        List<TaskNode> nodes = removeDuplicateIds(graph.nodes(), warnings, repairs);
        nodes = removeUnknownCapabilities(nodes, errors, repairs);
        nodes = repairDependencies(nodes, warnings, repairs);
        if (hasCycle(nodes)) {
            warnings.add("TASK_GRAPH_DEPENDENCY_CYCLE");
            nodes = nodes.stream().map(node -> node.withDependsOn(List.of())).toList();
            repairs.add("Removed dependencies because the planner produced a cyclic graph.");
        }
        validateSideEffectBoundaries(nodes, warnings);
        validateRequiredInputSources(nodes, warnings);
        TaskGraph repaired = graph.withNodes(nodes);
        if (repaired.nodes().isEmpty()) {
            errors.add("TASK_GRAPH_HAS_NO_VALID_NODES");
            repaired = repaired.withStatus("failed");
        }
        return new TaskGraphValidationResult(repaired, errors, warnings, repairs);
    }

    private List<TaskNode> removeDuplicateIds(List<TaskNode> nodes, List<String> warnings, List<String> repairs) {
        Set<String> seen = new LinkedHashSet<>();
        List<TaskNode> repaired = new ArrayList<>();
        int index = 1;
        for (TaskNode node : nodes) {
            String id = node.id();
            if (!seen.add(id)) {
                id = uniqueId(seen, node.id() + "_" + index++);
                warnings.add("DUPLICATE_NODE_ID:" + node.id());
                repairs.add("Renamed duplicate node id " + node.id() + " to " + id + ".");
                seen.add(id);
                repaired.add(new TaskNode(id, node.goal(), node.capabilityName(), node.inputs(), node.dependsOn(),
                        node.completionCriteria(), node.priority(), node.plannerRationale(), node.retryCount(),
                        node.status(), node.riskLevel(), node.observationId()));
            }
            else {
                repaired.add(node);
            }
        }
        return repaired;
    }

    private List<TaskNode> removeUnknownCapabilities(List<TaskNode> nodes, List<String> errors,
                                                     List<String> repairs) {
        List<TaskNode> repaired = nodes.stream()
                .filter(node -> capabilityRegistry.find(node.capabilityName()).isPresent())
                .toList();
        Set<String> keptIds = repaired.stream().map(TaskNode::id).collect(Collectors.toSet());
        nodes.stream()
                .filter(node -> !keptIds.contains(node.id()))
                .forEach(node -> {
                    errors.add("UNKNOWN_CAPABILITY:" + node.id() + ":" + node.capabilityName());
                    repairs.add("Removed node " + node.id() + " because capability " + node.capabilityName()
                            + " is not registered.");
                });
        return repaired;
    }

    private List<TaskNode> repairDependencies(List<TaskNode> nodes, List<String> warnings, List<String> repairs) {
        Set<String> ids = nodes.stream().map(TaskNode::id).collect(Collectors.toSet());
        return nodes.stream().map(node -> {
            List<String> dependencies = node.dependsOn().stream().filter(ids::contains).distinct().toList();
            if (dependencies.size() != node.dependsOn().size()) {
                warnings.add("INVALID_DEPENDENCY:" + node.id());
                repairs.add("Removed invalid dependencies from node " + node.id() + ".");
            }
            return node.withDependsOn(dependencies);
        }).toList();
    }

    private void validateSideEffectBoundaries(List<TaskNode> nodes, List<String> warnings) {
        for (TaskNode node : nodes) {
            CapabilityDescriptor capability = capabilityRegistry.find(node.capabilityName()).orElse(null);
            if (capability != null && (capability.sideEffects() || capability.requiresHumanApproval())
                    && node.dependsOn().isEmpty()) {
                warnings.add("SIDE_EFFECT_NODE_HAS_NO_UPSTREAM_PROPOSAL:" + node.id() + ":" + capability.name());
            }
        }
    }

    private void validateRequiredInputSources(List<TaskNode> nodes, List<String> warnings) {
        Set<String> ids = nodes.stream().map(TaskNode::id).collect(Collectors.toSet());
        for (TaskNode node : nodes) {
            CapabilityDescriptor capability = capabilityRegistry.find(node.capabilityName()).orElse(null);
            if (capability == null) {
                continue;
            }
            for (String input : capability.requiredInputs()) {
                if (hasInput(node.inputs(), input) || !node.dependsOn().isEmpty()) {
                    continue;
                }
                if (ids.contains(input)) {
                    continue;
                }
                warnings.add("REQUIRED_INPUT_NOT_BOUND:" + node.id() + ":" + input);
            }
        }
    }

    private boolean hasCycle(List<TaskNode> nodes) {
        Map<String, TaskNode> byId = nodes.stream().collect(Collectors.toMap(TaskNode::id, node -> node));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (TaskNode node : nodes) {
            if (visitHasCycle(node.id(), byId, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean visitHasCycle(String nodeId, Map<String, TaskNode> byId, Set<String> visiting,
                                  Set<String> visited) {
        if (visited.contains(nodeId)) {
            return false;
        }
        if (!visiting.add(nodeId)) {
            return true;
        }
        TaskNode node = byId.get(nodeId);
        if (node != null) {
            for (String dependency : node.dependsOn()) {
                if (visitHasCycle(dependency, byId, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    private boolean hasInput(Map<String, Object> inputs, String key) {
        Object value = inputs == null ? null : inputs.get(key);
        return value != null && !value.toString().isBlank();
    }

    private String uniqueId(Set<String> seen, String base) {
        String candidate = base;
        int index = 2;
        while (seen.contains(candidate)) {
            candidate = base + "_" + index++;
        }
        return candidate;
    }
}
