package com.example.marketing.core.skill;

import java.util.List;

public record SkillDescriptor(
        String name,
        String version,
        String description,
        String entryAgent,
        List<String> requiredContext,
        List<String> canEmit,
        List<String> permissions,
        String promptResource,
        List<String> evalSuites
) {
    public SkillDescriptor(String name, String description, String entryAgent, List<String> requiredContext,
                           List<String> canEmit) {
        this(name, "0.0.0", description, entryAgent,
                requiredContext == null ? List.of() : List.copyOf(requiredContext),
                canEmit == null ? List.of() : List.copyOf(canEmit), List.of(), "/skills/" + name + ".md",
                List.of());
    }
}
