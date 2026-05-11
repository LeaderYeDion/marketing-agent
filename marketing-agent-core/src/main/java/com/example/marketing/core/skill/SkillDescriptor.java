package com.example.marketing.core.skill;

import java.util.List;

public record SkillDescriptor(
        String name,
        String description,
        String entryAgent,
        List<String> requiredContext,
        List<String> canEmit
) {
}
