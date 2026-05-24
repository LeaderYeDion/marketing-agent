package com.example.marketing.core.capability;

import java.util.List;
import java.util.Map;

import com.example.marketing.core.skill.SkillDescriptor;

public record CapabilityDescriptor(
        String name,
        String description,
        List<String> requiredInputs,
        List<String> outputContract,
        List<String> permissions,
        boolean sideEffects,
        boolean requiresHumanApproval,
        String riskLevel,
        String provider,
        List<String> composableWith,
        List<String> fallbackCapabilityNames,
        String capabilityType,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<String> preconditions,
        List<String> postconditions,
        String executionMode,
        List<String> skillRefs
) {
    public CapabilityDescriptor {
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        outputContract = outputContract == null ? List.of() : List.copyOf(outputContract);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "medium" : riskLevel;
        provider = provider == null ? "" : provider;
        composableWith = composableWith == null ? List.of() : List.copyOf(composableWith);
        fallbackCapabilityNames = fallbackCapabilityNames == null ? List.of() : List.copyOf(fallbackCapabilityNames);
        capabilityType = capabilityType == null || capabilityType.isBlank() ? inferCapabilityType(sideEffects,
                permissions) : capabilityType;
        inputSchema = inputSchema == null ? defaultInputSchema(requiredInputs) : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? defaultOutputSchema(outputContract) : Map.copyOf(outputSchema);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        postconditions = postconditions == null ? List.of() : List.copyOf(postconditions);
        executionMode = executionMode == null || executionMode.isBlank() ? "deterministic" : executionMode;
        skillRefs = skillRefs == null ? List.of() : List.copyOf(skillRefs);
    }

    public CapabilityDescriptor(String name, String description, List<String> requiredInputs,
                                List<String> outputContract, List<String> permissions, boolean sideEffects,
                                boolean requiresHumanApproval, String riskLevel, String provider,
                                List<String> composableWith, List<String> fallbackCapabilityNames) {
        this(name, description, requiredInputs, outputContract, permissions, sideEffects, requiresHumanApproval,
                riskLevel, provider, composableWith, fallbackCapabilityNames, "", null, null, List.of(), List.of(),
                "", List.of());
    }

    public static CapabilityDescriptor fromSkill(SkillDescriptor skill) {
        return new CapabilityDescriptor(
                skill.name(),
                skill.summary(),
                skill.requiredInputs(),
                skill.outputContract().isEmpty() ? skill.canEmit() : skill.outputContract(),
                skill.permissions(),
                skill.sideEffects(),
                skill.requiresHumanApproval(),
                skill.riskLevel(),
                skill.entryAgent(),
                skill.composableWith(),
                skill.fallbackSkills(),
                skill.capabilityType(),
                defaultInputSchema(skill.requiredInputs()),
                defaultOutputSchema(skill.outputContract().isEmpty() ? skill.canEmit() : skill.outputContract()),
                skill.preconditions(),
                skill.postconditions(),
                skill.executionMode(),
                List.of(skill.name())
        );
    }

    private static String inferCapabilityType(boolean sideEffects, List<String> permissions) {
        if (sideEffects) {
            return "side-effect execution";
        }
        List<String> safePermissions = permissions == null ? List.of() : permissions;
        if (safePermissions.stream().anyMatch(permission -> permission.contains("write"))) {
            return "transformation";
        }
        return "read-only query";
    }

    private static Map<String, Object> defaultInputSchema(List<String> requiredInputs) {
        return Map.of(
                "type", "object",
                "required", requiredInputs == null ? List.of() : List.copyOf(requiredInputs)
        );
    }

    private static Map<String, Object> defaultOutputSchema(List<String> outputContract) {
        return Map.of(
                "type", "observation",
                "fields", outputContract == null ? List.of() : List.copyOf(outputContract)
        );
    }
}
