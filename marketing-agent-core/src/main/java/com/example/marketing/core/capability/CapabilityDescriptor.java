package com.example.marketing.core.capability;

import java.util.List;

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
        List<String> fallbackCapabilityNames
) {
    public CapabilityDescriptor {
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        outputContract = outputContract == null ? List.of() : List.copyOf(outputContract);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "medium" : riskLevel;
        provider = provider == null ? "" : provider;
        composableWith = composableWith == null ? List.of() : List.copyOf(composableWith);
        fallbackCapabilityNames = fallbackCapabilityNames == null ? List.of() : List.copyOf(fallbackCapabilityNames);
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
                skill.fallbackSkills()
        );
    }
}
