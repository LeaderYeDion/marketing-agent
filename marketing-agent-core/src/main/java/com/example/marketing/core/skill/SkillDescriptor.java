package com.example.marketing.core.skill;

import java.util.List;

public record SkillDescriptor(
        String name,
        String version,
        String summary,
        List<String> intentHints,
        String entryAgent,
        List<String> requiredInputs,
        List<String> canEmit,
        List<String> permissions,
        boolean sideEffects,
        boolean requiresHumanApproval,
        String riskLevel,
        String skillResource,
        List<String> evalSuites
) {
    public SkillDescriptor(String name, String summary, String entryAgent, List<String> requiredInputs,
                           List<String> canEmit) {
        this(name, "0.0.0", summary, List.of(), entryAgent,
                requiredInputs == null ? List.of() : List.copyOf(requiredInputs),
                canEmit == null ? List.of() : List.copyOf(canEmit), List.of(), false, false, "medium",
                "/skills/" + name + "/skill.md", List.of());
    }
}
