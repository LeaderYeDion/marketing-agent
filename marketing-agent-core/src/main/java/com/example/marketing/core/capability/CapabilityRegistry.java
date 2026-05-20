package com.example.marketing.core.capability;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.marketing.core.skill.SkillRegistry;

@Service
public class CapabilityRegistry {
    private final SkillRegistry skillRegistry;

    public CapabilityRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public List<CapabilityDescriptor> list() {
        return skillRegistry.list().stream()
                .map(CapabilityDescriptor::fromSkill)
                .toList();
    }

    public Optional<CapabilityDescriptor> find(String name) {
        return list().stream()
                .filter(capability -> capability.name().equals(name))
                .findFirst();
    }

}
