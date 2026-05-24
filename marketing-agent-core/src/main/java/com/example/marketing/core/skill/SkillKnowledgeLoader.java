package com.example.marketing.core.skill;

import java.util.List;
import java.util.Optional;

public interface SkillKnowledgeLoader {
    List<SkillPackageDescriptor> listSummaries();

    Optional<LoadedSkill> loadSkill(String skillName);
}
