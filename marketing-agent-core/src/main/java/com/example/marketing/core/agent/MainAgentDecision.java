package com.example.marketing.core.agent;

import java.util.List;
import java.util.Map;

public record MainAgentDecision(
        String action,
        String skillName,
        List<String> candidateSkills,
        String delegateTo,
        String reply,
        Map<String, Object> inputs,
        String compressedContext
) {
    public MainAgentDecision {
        candidateSkills = candidateSkills == null ? List.of() : List.copyOf(candidateSkills);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
}
