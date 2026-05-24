package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.marketing.core.skill.SkillKnowledgeLoader;

@Service
public class SubAgentProfileValidator {
    private final SkillKnowledgeLoader skillKnowledgeLoader;

    public SubAgentProfileValidator(SkillKnowledgeLoader skillKnowledgeLoader) {
        this.skillKnowledgeLoader = skillKnowledgeLoader;
    }

    public List<String> validate(List<SubAgentProfile> profiles) {
        Set<String> skillNames = skillKnowledgeLoader.listSummaries().stream()
                .map(com.example.marketing.core.skill.SkillPackageDescriptor::name)
                .collect(Collectors.toSet());
        List<String> errors = new ArrayList<>();
        for (SubAgentProfile profile : profiles == null ? List.<SubAgentProfile>of() : profiles) {
            if (profile.name() == null || profile.name().isBlank()) {
                errors.add("SUBAGENT_NAME_EMPTY");
            }
            if (profile.description().isBlank()) {
                errors.add("SUBAGENT_DESCRIPTION_EMPTY:" + profile.name());
            }
            profile.allowedSkills().stream()
                    .filter(skill -> !skill.isBlank() && !skillNames.contains(skill))
                    .forEach(skill -> errors.add("SUBAGENT_UNKNOWN_SKILL:" + profile.name() + ":" + skill));
            boolean hasWritePermission = profile.permissionProfile().stream()
                    .anyMatch(permission -> permission.contains("write") || permission.contains("execute")
                            || permission.contains("operation"));
            if (hasWritePermission) {
                errors.add("SUBAGENT_WRITE_PERMISSION_REQUIRES_EXPLICIT_HITL_BOUNDARY:" + profile.name());
            }
        }
        return errors;
    }
}
