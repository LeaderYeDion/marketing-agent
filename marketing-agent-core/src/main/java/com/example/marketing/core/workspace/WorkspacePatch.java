package com.example.marketing.core.workspace;

public record WorkspacePatch(
        String operation,
        String target,
        String replacement
) {
    public WorkspacePatch {
        operation = operation == null || operation.isBlank() ? "append" : operation;
        target = target == null ? "" : target;
        replacement = replacement == null ? "" : replacement;
    }
}
