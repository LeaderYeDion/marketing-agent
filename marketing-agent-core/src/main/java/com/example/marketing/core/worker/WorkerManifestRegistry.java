package com.example.marketing.core.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class WorkerManifestRegistry {
    private final List<WorkerManifestDescriptor> manifests = loadManifests();

    public List<WorkerManifestDescriptor> list() {
        return manifests;
    }

    public Optional<WorkerManifestDescriptor> find(String name) {
        return manifests.stream().filter(manifest -> manifest.name().equals(name)).findFirst();
    }

    private List<WorkerManifestDescriptor> loadManifests() {
        List<String> names = readResource("/skills/index.txt")
                .map(content -> content.lines()
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .toList())
                .orElse(List.of());
        List<WorkerManifestDescriptor> loaded = names.stream()
                .map(this::loadManifest)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (loaded.isEmpty()) {
            loaded = fallbackManifests();
        }
        return appendDelegateTask(loaded);
    }

    private Optional<WorkerManifestDescriptor> loadManifest(String name) {
        return readResource("/skills/" + name + "/manifest.yaml").map(this::parseManifest);
    }

    private WorkerManifestDescriptor parseManifest(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains(":"))
                .forEach(line -> {
                    int colon = line.indexOf(':');
                    values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                });
        String name = values.getOrDefault("name", "");
        List<String> output = csv(values.getOrDefault("outputContract", values.get("canEmit")));
        List<String> skillRefs = csv(values.get("skillRefs"));
        if (skillRefs.isEmpty() && !name.isBlank()) {
            skillRefs = List.of(name);
        }
        return new WorkerManifestDescriptor(
                name,
                values.getOrDefault("description", values.getOrDefault("summary", "")),
                values.getOrDefault("provider", values.getOrDefault("entryAgent", "")),
                values.getOrDefault("executionMode", inferExecutionMode(bool(values.get("sideEffects")),
                        values.getOrDefault("entryAgent", ""))),
                csv(values.getOrDefault("requiredInputs", values.get("requiredContext"))),
                output,
                csv(values.get("permissions")),
                bool(values.get("sideEffects")),
                bool(values.get("requiresHumanApproval")),
                values.getOrDefault("riskLevel", "medium"),
                csv(values.get("composableWith")),
                csv(values.getOrDefault("fallbacks", values.get("fallbackSkills"))),
                csv(values.get("preconditions")),
                csv(values.get("postconditions")),
                skillRefs,
                csv(values.get("evalSuites")),
                values.getOrDefault("workerType", ""),
                Map.of(),
                Map.of()
        );
    }

    private List<WorkerManifestDescriptor> appendDelegateTask(List<WorkerManifestDescriptor> loaded) {
        if (loaded.stream().anyMatch(manifest -> "delegate_task".equals(manifest.name()))) {
            return loaded;
        }
        java.util.ArrayList<WorkerManifestDescriptor> all = new java.util.ArrayList<>(loaded);
        all.add(new WorkerManifestDescriptor(
                "delegate_task",
                "Delegate an isolated read-only analysis task to a bounded sub-agent and return summary plus workspace refs.",
                "delegate_task_provider",
                "delegate",
                List.of("agentName", "task", "expectedOutput"),
                List.of("summary", "confidence", "workspace_refs"),
                List.of("workspace.read"),
                false,
                false,
                "low",
                List.of("rule_inquiry", "activity_rule_check", "spreadsheet_query_product", "copywriting"),
                List.of(),
                List.of("agentName exists in SubAgentProfileRegistry"),
                List.of("delegation result is normalized into Observation"),
                List.of(),
                List.of(),
                "delegation",
                Map.of("type", "object", "required", List.of("agentName", "task", "expectedOutput")),
                Map.of("type", "observation", "required", List.of("summary", "confidence", "workspace_refs"))
        ));
        return List.copyOf(all);
    }

    private String inferExecutionMode(boolean sideEffects, String provider) {
        if (sideEffects) {
            return "deterministic";
        }
        if (provider != null && provider.contains("rule_inquiry")) {
            return "react";
        }
        return "deterministic";
    }

    private Optional<String> readResource(String resourceName) {
        try (InputStream stream = WorkerManifestRegistry.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load worker manifest resource: " + resourceName, ex);
        }
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private boolean bool(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private List<WorkerManifestDescriptor> fallbackManifests() {
        return List.of(new WorkerManifestDescriptor("rule_inquiry",
                "Answer marketing activity, promotion, enrollment and rule questions.",
                "rule_inquiry_provider", "react", List.of("question"), List.of("markdown", "citations"),
                List.of("knowledge.retrieve"), false, false, "low", List.of(), List.of(), List.of(), List.of(),
                List.of("rule_inquiry"), List.of(), "read-only query", Map.of(), Map.of()));
    }
}

