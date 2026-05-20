package com.example.marketing.core.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.llm.JsonSupport;
import com.example.marketing.core.llm.LlmGateway;
import com.example.marketing.core.llm.LlmRequest;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TaskPlanner {
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskPlanner(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    public TaskGraph plan(MarketingRequest request, HarnessContext context) {
        String graphId = "tg_" + UUID.randomUUID().toString().substring(0, 8);
        String userGoal = request.query() == null ? "" : request.query();
        PlanDraft draft = planWithModel(request, context);
        if (draft.nodes().isEmpty()) {
            draft = deterministicSafetyFallback(request, context);
        }
        List<TaskNode> nodes = validateAndNormalizeNodes(draft.nodes(), request, context);
        if (nodes.isEmpty()) {
            draft = deterministicSafetyFallback(request, context);
            nodes = validateAndNormalizeNodes(draft.nodes(), request, context);
        }
        return new TaskGraph(graphId, userGoal, nodes, draft.rationale(), draft.answerStrategy(),
                nodes.isEmpty() ? "empty" : "planned");
    }

    private PlanDraft planWithModel(MarketingRequest request, HarnessContext context) {
        try {
            String raw = llmGateway.generateText(LlmRequest.simple("task-graph-planning",
                    plannerSystemMessage(context),
                    List.of(ConversationMessage.user(plannerUserMessage(request, context), Map.of()))));
            return parsePlan(raw);
        }
        catch (Exception ex) {
            return PlanDraft.empty();
        }
    }

    private PlanDraft parsePlan(String raw) {
        try {
            JsonNode root = objectMapper.readTree(JsonSupport.extractJsonObject(raw));
            List<PlannedNode> nodes = new ArrayList<>();
            JsonNode nodeArray = root.path("nodes");
            if (nodeArray.isArray()) {
                for (JsonNode item : nodeArray) {
                    nodes.add(new PlannedNode(
                            text(item, "id"),
                            text(item, "goal"),
                            firstNonBlank(text(item, "capabilityName"), text(item, "capability")),
                            stringList(item.path("dependsOn")),
                            objectMap(item.path("inputs")),
                            text(item, "completionCriteria"),
                            intValue(item.path("priority"), 100),
                            text(item, "rationale")
                    ));
                }
            }
            return new PlanDraft(text(root, "rationale"), text(root, "answerStrategy"), nodes);
        }
        catch (Exception ex) {
            return PlanDraft.empty();
        }
    }

    private List<TaskNode> validateAndNormalizeNodes(List<PlannedNode> plannedNodes, MarketingRequest request,
                                                     HarnessContext context) {
        Set<String> knownCapabilities = context.capabilities().stream()
                .map(CapabilityDescriptor::name)
                .collect(Collectors.toSet());
        List<TaskNode> normalized = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        int index = 1;
        for (PlannedNode planned : plannedNodes) {
            if (!knownCapabilities.contains(planned.capabilityName())) {
                continue;
            }
            String id = normalizeId(planned.id(), index);
            while (usedIds.contains(id)) {
                id = "node_" + index++;
            }
            usedIds.add(id);
            Map<String, Object> inputs = mergedInputs(planned.inputs(), request, planned.capabilityName());
            normalized.add(TaskNode.planned(
                    id,
                    firstNonBlank(planned.goal(), "Use " + planned.capabilityName() + " for the user's goal."),
                    planned.capabilityName(),
                    inputs,
                    planned.dependsOn(),
                    planned.completionCriteria(),
                    planned.priority(),
                    planned.rationale()
            ));
            index++;
        }
        return removeInvalidDependencies(normalized);
    }

    private List<TaskNode> removeInvalidDependencies(List<TaskNode> nodes) {
        Set<String> ids = nodes.stream().map(TaskNode::id).collect(Collectors.toSet());
        return nodes.stream()
                .map(node -> new TaskNode(
                        node.id(),
                        node.goal(),
                        node.capabilityName(),
                        node.inputs(),
                        node.dependsOn().stream().filter(ids::contains).distinct().toList(),
                        node.completionCriteria(),
                        node.priority(),
                        node.plannerRationale(),
                        node.retryCount(),
                        node.status(),
                        node.riskLevel(),
                        node.observationId()
                ))
                .toList();
    }

    private PlanDraft deterministicSafetyFallback(MarketingRequest request, HarnessContext context) {
        List<String> requested = requestedCapabilities(request.variables(), context);
        if (!requested.isEmpty()) {
            List<PlannedNode> nodes = new ArrayList<>();
            List<String> dependsOn = List.of();
            int index = 1;
            for (String capability : requested) {
                String id = "node_" + index++;
                nodes.add(new PlannedNode(id, "Execute explicitly requested capability " + capability, capability,
                        dependsOn, Map.of(), "", 100, "User or caller supplied requested_capabilities."));
                dependsOn = List.of(id);
            }
            return new PlanDraft("Planner fallback used explicit requested_capabilities.",
                    "Summarize capability observations in dependency order.", nodes);
        }
        return context.capabilities().stream()
                .filter(capability -> allRequiredInputsPresent(capability, request.variables()))
                .findFirst()
                .map(capability -> new PlanDraft(
                        "Planner fallback selected a capability whose declared required inputs are present.",
                        "Return the capability observation.",
                        List.of(new PlannedNode("node_1", "Execute capability with already supplied inputs.",
                                capability.name(), List.of(), Map.of(), "", 100,
                                "No language understanding fallback was used."))))
                .orElseGet(() -> new PlanDraft(
                        "Planner fallback selected the safest read-only inquiry capability.",
                        "Ask for clarification if the observation is insufficient.",
                        List.of(new PlannedNode("node_1", "Clarify or answer the user's marketing question.",
                                "rule_inquiry", List.of(), Map.of(), "", 100,
                                "No language understanding fallback was used."))));
    }

    private Map<String, Object> mergedInputs(Map<String, Object> plannedInputs, MarketingRequest request,
                                             String capabilityName) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (request.variables() != null) {
            inputs.putAll(request.variables());
        }
        if (plannedInputs != null) {
            inputs.putAll(plannedInputs);
        }
        putIfNotBlank(inputs, "question", request.query());
        putIfNotBlank(inputs, "channel", request.channel());
        putIfNotBlank(inputs, "product", request.product());
        putIfNotBlank(inputs, "audience", request.audience());
        if (request.goals() != null && !request.goals().isEmpty()) {
            inputs.put("goals", request.goals());
        }
        inputs.put("capability_name", capabilityName);
        return inputs;
    }

    private String plannerSystemMessage(HarnessContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("TASK_GRAPH_PLANNER\n");
        builder.append("You are the natural-language understanding and task-planning kernel for a marketing ");
        builder.append("agent harness. The Java runtime will not interpret the user's wording for you. ");
        builder.append("You must infer goals, order, dependencies, missing evidence, and capability composition ");
        builder.append("from the user's natural language and the current context.\n\n");
        builder.append("Plan a DAG. Independent nodes may have an empty dependsOn array and will be executed ");
        builder.append("in parallel by the harness. Dependent nodes must name prerequisite node ids. ");
        builder.append("Only choose capabilities from the catalog. Do not invent capability names.\n\n");
        builder.append("Capability catalog:\n");
        for (CapabilityDescriptor capability : context.capabilities()) {
            builder.append("- name=").append(capability.name())
                    .append("; description=").append(capability.description())
                    .append("; requiredInputs=").append(capability.requiredInputs())
                    .append("; outputContract=").append(capability.outputContract())
                    .append("; permissions=").append(capability.permissions())
                    .append("; sideEffects=").append(capability.sideEffects())
                    .append("; requiresHumanApproval=").append(capability.requiresHumanApproval())
                    .append("; risk=").append(capability.riskLevel())
                    .append("; composableWith=").append(capability.composableWith())
                    .append("; fallbacks=").append(capability.fallbackCapabilityNames())
                    .append("\n");
        }
        builder.append("\nReturn only JSON with this exact shape:\n");
        builder.append("""
                {
                  "rationale": "why this decomposition solves the user goal",
                  "answerStrategy": "how final observations should be combined for the user",
                  "nodes": [
                    {
                      "id": "node_1",
                      "goal": "concrete business sub-goal",
                      "capabilityName": "one catalog capability name",
                      "dependsOn": [],
                      "inputs": {"question": "preserve or rewrite the relevant user question"},
                      "completionCriteria": "what observation proves this node is done",
                      "priority": 100,
                      "rationale": "why this capability is appropriate"
                    }
                  ]
                }
                """);
        return builder.toString();
    }

    private String plannerUserMessage(MarketingRequest request, HarnessContext context) {
        return """
                User input:
                %s

                Request variables:
                %s

                Current harness context:
                %s
                """.formatted(
                request.query() == null ? "" : request.query(),
                request.variables() == null ? Map.of() : request.variables(),
                context.compressedContext());
    }

    private List<String> requestedCapabilities(Map<String, Object> variables, HarnessContext context) {
        Object requested = variables == null ? null : variables.get("requested_capabilities");
        if (requested == null) {
            return List.of();
        }
        Set<String> known = context.capabilities().stream().map(CapabilityDescriptor::name).collect(Collectors.toSet());
        if (requested instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(known::contains).distinct().toList();
        }
        return java.util.Arrays.stream(requested.toString().split("\\s*(?:,|->|=>|\\||/|;|\\x{FF0C}|\\x{3001})\\s*"))
                .map(String::trim)
                .filter(known::contains)
                .distinct()
                .toList();
    }

    private boolean allRequiredInputsPresent(CapabilityDescriptor capability, Map<String, Object> variables) {
        if (capability.requiredInputs().isEmpty() || variables == null) {
            return false;
        }
        return capability.requiredInputs().stream()
                .allMatch(input -> variables.get(input) != null && !variables.get(input).toString().isBlank());
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private int intValue(JsonNode node, int fallback) {
        return node.isInt() ? node.asInt() : fallback;
    }

    private String normalizeId(String id, int index) {
        String value = id == null || id.isBlank() ? "node_" + index : id.trim();
        return value.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record PlanDraft(String rationale, String answerStrategy, List<PlannedNode> nodes) {
        private PlanDraft {
            rationale = rationale == null ? "" : rationale;
            answerStrategy = answerStrategy == null ? "" : answerStrategy;
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        private static PlanDraft empty() {
            return new PlanDraft("", "", List.of());
        }
    }

    private record PlannedNode(
            String id,
            String goal,
            String capabilityName,
            List<String> dependsOn,
            Map<String, Object> inputs,
            String completionCriteria,
            int priority,
            String rationale
    ) {
        private PlannedNode {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            completionCriteria = completionCriteria == null ? "" : completionCriteria;
            rationale = rationale == null ? "" : rationale;
        }
    }
}
