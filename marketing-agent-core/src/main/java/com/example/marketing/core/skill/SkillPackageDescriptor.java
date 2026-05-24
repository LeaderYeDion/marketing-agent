package com.example.marketing.core.skill;

import java.util.List;

public record SkillPackageDescriptor(
        String name,
        String description,
        String resource,
        List<String> allowedCapabilities
) {
    public SkillPackageDescriptor {
        description = description == null ? "" : description;
        resource = resource == null || resource.isBlank() ? "/skills/" + name + "/SKILL.md" : resource;
        allowedCapabilities = allowedCapabilities == null ? List.of() : List.copyOf(allowedCapabilities);
    }
}
