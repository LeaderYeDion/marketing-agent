package com.example.marketing.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GoldenCaseRepository {
    public List<EvalCase> loadDefaultCases() {
        return load("/eval/golden-cases.txt");
    }

    public List<EvalCase> load(String resourceName) {
        try (InputStream stream = GoldenCaseRepository.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return List.of();
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(content.split("(?m)^---\\s*$"))
                    .map(String::trim)
                    .filter(block -> !block.isBlank())
                    .map(this::parseCase)
                    .toList();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load eval cases: " + resourceName, ex);
        }
    }

    private EvalCase parseCase(String block) {
        Map<String, String> values = new LinkedHashMap<>();
        block.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains("="))
                .forEach(line -> {
                    int split = line.indexOf('=');
                    values.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
                });
        return new EvalCase(
                values.getOrDefault("id", ""),
                values.getOrDefault("query", ""),
                values.getOrDefault("expectedAction", ""),
                values.getOrDefault("expectedSkillName", ""),
                values.getOrDefault("expectedDelegateTo", ""),
                firstCsv(values, "expectedWorkers", "expectedCapabilities"),
                csv(values.get("forbiddenWorkers")),
                dependencies(values.get("expectedDependencies")),
                values.getOrDefault("expectedDelegateAgent", ""),
                csv(values.get("expectedMissingInputs")),
                values.getOrDefault("expectedHarnessStatus", ""),
                intValue(values.get("minTaskNodes")),
                stringMap(values.get("expectedObservationStatuses")),
                csv(values.get("expectedEvidenceKeys")),
                csv(values.get("expectedArtifactKeys")),
                csv(values.get("expectedWorkspaceRefs")),
                csv(values.get("goldEvidenceIds")),
                csv(values.get("goldAnswerFacts")),
                csv(values.get("forbiddenFacts")),
                csv(values.get("expectedCitationPaths")),
                csv(values.get("mustContain")),
                csv(values.get("forbidden")),
                variables(values)
        );
    }

    private List<String> firstCsv(Map<String, String> values, String first, String fallback) {
        List<String> items = csv(values.get(first));
        return items.isEmpty() ? csv(values.get(fallback)) : items;
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private int intValue(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Map<String, List<String>> dependencies(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String entry : value.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                result.put(parts[0].trim(), Arrays.stream(parts[1].split("\\+"))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .toList());
            }
        }
        return result;
    }

    private Map<String, String> stringMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                result.put(parts[0].trim(), parts[1].trim());
            }
        }
        return result;
    }

    private Map<String, Object> variables(Map<String, String> values) {
        Map<String, Object> variables = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith("variable.")) {
                variables.put(key.substring("variable.".length()), value);
            }
        });
        return variables;
    }
}
