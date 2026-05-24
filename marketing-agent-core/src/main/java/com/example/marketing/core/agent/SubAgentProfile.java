package com.example.marketing.core.agent;

import java.util.List;
import java.util.Map;

public record SubAgentProfile(
        String name,
        String description,
        String systemPromptResource,
        List<String> allowedTools,
        List<String> permissionProfile,
        List<String> allowedSkills,
        int maxSteps,
        int maxTokens,
        Map<String, Object> outputSchema
) {
    public SubAgentProfile {
        description = description == null ? "" : description;
        systemPromptResource = systemPromptResource == null ? "" : systemPromptResource;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        permissionProfile = permissionProfile == null ? List.of() : List.copyOf(permissionProfile);
        allowedSkills = allowedSkills == null ? List.of() : List.copyOf(allowedSkills);
        maxSteps = maxSteps <= 0 ? 6 : maxSteps;
        maxTokens = maxTokens <= 0 ? 4_000 : maxTokens;
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
    }

    public static SubAgentProfile general(String name, String description) {
        return new SubAgentProfile(name, description, "", List.of("read_workspace", "search_workspace",
                "search_knowledge_base", "read_skill"), List.of("workspace.read", "knowledge.retrieve"),
                List.of(), 6, 4_000, Map.of("type", "observation",
                "required", List.of("summary", "confidence", "workspace_refs")));
    }
}
