package com.example.marketing.core.harness;

import java.util.List;

import com.example.marketing.core.task.TaskGraph;

public record TaskGraphValidationResult(
        TaskGraph graph,
        List<String> errors,
        List<String> warnings,
        List<String> repairs
) {
    public TaskGraphValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        repairs = repairs == null ? List.of() : List.copyOf(repairs);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
