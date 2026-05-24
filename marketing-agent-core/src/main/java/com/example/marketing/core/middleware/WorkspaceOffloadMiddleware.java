package com.example.marketing.core.middleware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.workspace.AgentWorkspace;
import com.example.marketing.core.workspace.WorkspaceDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class WorkspaceOffloadMiddleware implements HarnessMiddleware {
    private final AgentWorkspace workspace;
    private final ObjectMapper objectMapper;

    public WorkspaceOffloadMiddleware(AgentWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Observation beforeObservationCommit(HarnessInvocationContext invocation, TaskGraph graph,
                                               Observation observation) {
        WorkspaceRefs refs = writeObservation(invocation, graph, observation);
        Map<String, Object> evidence = new LinkedHashMap<>(observation.evidence());
        evidence.put("workspace_refs", refs.asMap());
        Map<String, Object> artifacts = new LinkedHashMap<>(observation.artifacts());
        artifacts.put("workspace_observation_path", refs.observationPath());
        if (!refs.artifactRefs().isEmpty()) {
            artifacts.put("workspace_artifact_paths", refs.artifactRefs().stream()
                    .map(ref -> ref.get("workspace_path"))
                    .toList());
        }
        return new Observation(observation.id(), observation.runId(), observation.taskNodeId(),
                observation.workerName(), observation.status(), observation.summary(), evidence, artifacts,
                observation.confidence(), observation.missingInputs(), observation.riskLevel(),
                observation.requiresApproval(), observation.actionProposal(), observation.errorType(),
                observation.retryable(), observation.visibleObjects(), observation.messagesToCommit(),
                observation.statePatch());
    }

    private WorkspaceRefs writeObservation(HarnessInvocationContext invocation, TaskGraph graph,
                                           Observation observation) {
        List<Map<String, Object>> evidenceRefs = writePartDocuments(invocation, graph, observation,
                "evidence", observation.evidence());
        List<Map<String, Object>> artifactRefs = writePartDocuments(invocation, graph, observation,
                "artifacts", observation.artifacts());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", observation.id());
        payload.put("run_id", observation.runId());
        payload.put("task_graph_id", graph == null ? "" : graph.id());
        payload.put("task_node_id", observation.taskNodeId());
        payload.put("worker_name", observation.workerName());
        payload.put("status", observation.status());
        payload.put("summary", observation.summary());
        payload.put("evidence", observation.evidence());
        payload.put("artifacts", observation.artifacts());
        payload.put("confidence", observation.confidence());
        payload.put("missing_inputs", observation.missingInputs());
        payload.put("risk_level", observation.riskLevel());
        payload.put("requires_approval", observation.requiresApproval());
        payload.put("error_type", observation.errorType());
        payload.put("retryable", observation.retryable());
        payload.put("visible_objects", observation.visibleObjects());
        payload.put("messages_to_commit", observation.messagesToCommit());
        payload.put("state_patch", observation.statePatch());
        payload.put("workspace_refs", Map.of("evidence", evidenceRefs, "artifacts", artifactRefs));

        String path = "/observations/" + safePathSegment(observation.runId()) + "/"
                + safePathSegment(observation.taskNodeId()) + "_" + safePathSegment(observation.id()) + ".json";
        WorkspaceDocument document = workspace.write(invocation.session().conversationId(), path, toJson(payload),
                Map.of("type", "observation",
                        "run_id", observation.runId(),
                        "task_graph_id", graph == null ? "" : graph.id(),
                        "task_node_id", observation.taskNodeId(),
                        "worker_name", observation.workerName(),
                        "status", observation.status()));
        WorkspaceRefs refs = new WorkspaceRefs(document.path(), evidenceRefs, artifactRefs);
        invocation.put("workspace_refs:" + observation.id(), refs.asMap());
        return refs;
    }

    private List<Map<String, Object>> writePartDocuments(HarnessInvocationContext invocation, TaskGraph graph,
                                                         Observation observation, String collection,
                                                         Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        values.forEach((key, value) -> {
            String path = "/" + collection + "/" + safePathSegment(observation.runId()) + "/"
                    + safePathSegment(observation.taskNodeId()) + "_" + safePathSegment(observation.id())
                    + "_" + safePathSegment(key) + ".json";
            WorkspaceDocument document = workspace.write(invocation.session().conversationId(), path, toJson(Map.of(
                            "observation_id", observation.id(),
                            "run_id", observation.runId(),
                            "task_graph_id", graph == null ? "" : graph.id(),
                            "task_node_id", observation.taskNodeId(),
                            "worker_name", observation.workerName(),
                            "kind", collection,
                            "key", key,
                            "value", value
                    )),
                    Map.of("type", collection,
                            "observation_id", observation.id(),
                            "run_id", observation.runId(),
                            "task_graph_id", graph == null ? "" : graph.id(),
                            "task_node_id", observation.taskNodeId(),
                            "worker_name", observation.workerName(),
                            "key", key));
            refs.add(Map.of("kind", collection, "key", key, "workspace_path", document.path()));
        });
        return refs;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(payload);
        }
    }

    private String safePathSegment(String value) {
        String safe = value == null || value.isBlank() ? "unknown" : value;
        return safe.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private record WorkspaceRefs(String observationPath, List<Map<String, Object>> evidenceRefs,
                                 List<Map<String, Object>> artifactRefs) {
        private Map<String, Object> asMap() {
            return Map.of("observation_path", observationPath,
                    "evidence", evidenceRefs,
                    "artifacts", artifactRefs);
        }
    }
}

