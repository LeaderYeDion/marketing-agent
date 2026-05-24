package com.example.marketing.core.worker;

import java.util.List;
import java.util.Map;

public record WorkerManifestDescriptor(
        String name,
        String description,
        String provider,
        String executionMode,
        List<String> requiredInputs,
        List<String> outputContract,
        List<String> permissions,
        boolean sideEffects,
        boolean requiresHumanApproval,
        String riskLevel,
        List<String> composableWith,
        List<String> fallbacks,
        List<String> preconditions,
        List<String> postconditions,
        List<String> skillRefs,
        List<String> evalSuites,
        String workerType,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
) {
    public WorkerManifestDescriptor {
        description = description == null ? "" : description;
        provider = provider == null ? "" : provider;
        executionMode = executionMode == null || executionMode.isBlank() ? "deterministic" : executionMode;
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        outputContract = outputContract == null ? List.of() : List.copyOf(outputContract);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "medium" : riskLevel;
        composableWith = composableWith == null ? List.of() : List.copyOf(composableWith);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        postconditions = postconditions == null ? List.of() : List.copyOf(postconditions);
        skillRefs = skillRefs == null ? List.of() : List.copyOf(skillRefs);
        evalSuites = evalSuites == null ? List.of() : List.copyOf(evalSuites);
        workerType = workerType == null ? "" : workerType;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
    }

    public WorkerDescriptor toWorkerDescriptor() {
        return new WorkerDescriptor(name, description, requiredInputs, outputContract, permissions, sideEffects,
                requiresHumanApproval, riskLevel, provider, composableWith, fallbacks, workerType,
                inputSchema.isEmpty() ? null : inputSchema, outputSchema.isEmpty() ? null : outputSchema,
                preconditions, postconditions, executionMode, skillRefs);
    }
}

