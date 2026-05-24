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
        List<String> evalSuites,
        List<String> outputContract,
        List<String> composableWith,
        List<String> fallbackSkills,
        List<String> preconditions,
        List<String> postconditions,
        String owner,
        String workerType,
        String executionMode
) {
    public SkillDescriptor {
        intentHints = intentHints == null ? List.of() : List.copyOf(intentHints);
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        canEmit = canEmit == null ? List.of() : List.copyOf(canEmit);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        evalSuites = evalSuites == null ? List.of() : List.copyOf(evalSuites);
        outputContract = outputContract == null ? List.of() : List.copyOf(outputContract);
        composableWith = composableWith == null ? List.of() : List.copyOf(composableWith);
        fallbackSkills = fallbackSkills == null ? List.of() : List.copyOf(fallbackSkills);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        postconditions = postconditions == null ? List.of() : List.copyOf(postconditions);
        owner = owner == null ? "" : owner;
        workerType = workerType == null ? "" : workerType;
        executionMode = executionMode == null ? "" : executionMode;
    }

    public SkillDescriptor(String name, String summary, String entryAgent, List<String> requiredInputs,
                           List<String> canEmit) {
        this(name, "0.0.0", summary, List.of(), entryAgent,
                requiredInputs == null ? List.of() : List.copyOf(requiredInputs),
                canEmit == null ? List.of() : List.copyOf(canEmit), List.of(), false, false, "medium",
                "/skills/" + name + "/SKILL.md", List.of(), canEmit, List.of(), List.of(), List.of(), List.of(), "",
                "", "");
    }
}

