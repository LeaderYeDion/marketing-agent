package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.marketing.api.MarketingResponse;

public class SystemTraceCapture {
    public SystemTrace capture(MarketingResponse response) {
        Map<String, Object> metadata = response == null || response.metadata() == null ? Map.of()
                : response.metadata();
        Map<String, Object> plannerOutput = map(metadata.get("decision"));
        Map<String, Object> taskGraph = map(metadata.get("taskGraph"));
        List<Map<String, Object>> observations = listOfMaps(metadata.get("observations"));
        List<Map<String, Object>> trace = listOfMaps(metadata.get("harnessTrace"));
        return new SystemTrace(
                plannerOutput,
                taskGraph,
                workerSequence(plannerOutput, taskGraph),
                observations,
                nestedMaps(observations, "evidence"),
                nestedMaps(observations, "artifacts"),
                map(metadata.get("workspaceRefs")),
                listOfMaps(metadata.get("workspace")),
                ragRetrievedChunks(metadata, observations),
                stringList(metadata.get("pendingActions")),
                traceByType(trace, "recovery"),
                trace,
                listOfMaps(metadata.get("observationEvaluations")),
                listOfMaps(metadata.get("riskAssessments")),
                listOfMaps(metadata.get("workers")),
                map(metadata.get("harness"))
        );
    }

    static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            Map<String, Object> map = map(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    static List<String> stringList(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text);
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> workerSequence(Map<String, Object> plannerOutput, Map<String, Object> taskGraph) {
        List<String> sequence = stringList(plannerOutput.get("workerSequence"));
        if (!sequence.isEmpty()) {
            return sequence;
        }
        return taskNodes(taskGraph).stream()
                .map(node -> stringValue(node.get("workerName")))
                .filter(worker -> !worker.isBlank())
                .distinct()
                .toList();
    }

    private List<Map<String, Object>> nestedMaps(List<Map<String, Object>> parents, String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> parent : parents) {
            Object value = parent.get(key);
            if (value instanceof Map<?, ?> map) {
                map.forEach((nestedKey, nestedValue) -> result.add(Map.of(
                        "observationId", stringValue(parent.get("id")),
                        "key", String.valueOf(nestedKey),
                        "value", nestedValue == null ? "" : nestedValue
                )));
            }
            else {
                result.addAll(listOfMaps(value));
            }
        }
        return result;
    }

    private List<Map<String, Object>> ragRetrievedChunks(Map<String, Object> metadata,
                                                         List<Map<String, Object>> observations) {
        List<Map<String, Object>> explicit = listOfMaps(metadata.get("ragRetrievedChunks"));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> evidence : nestedMaps(observations, "evidence")) {
            String text = evidence.toString().toLowerCase();
            if (text.contains("chunk") || text.contains("citation") || text.contains("source_uri")
                    || text.contains("document")) {
                result.add(evidence);
            }
        }
        return result;
    }

    private List<Map<String, Object>> traceByType(List<Map<String, Object>> trace, String token) {
        return trace.stream()
                .filter(event -> stringValue(event.get("eventType")).toLowerCase().contains(token))
                .toList();
    }

    static List<Map<String, Object>> taskNodes(Map<String, Object> taskGraph) {
        return listOfMaps(taskGraph.get("nodes"));
    }
}
