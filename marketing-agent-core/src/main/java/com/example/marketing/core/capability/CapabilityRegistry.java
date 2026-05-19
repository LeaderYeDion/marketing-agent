package com.example.marketing.core.capability;

import java.util.ArrayList;
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
        List<CapabilityDescriptor> capabilities = new ArrayList<>();
        skillRegistry.list().stream()
                .map(CapabilityDescriptor::fromSkill)
                .forEach(capabilities::add);
        capabilities.add(copywritingCapability());
        return List.copyOf(capabilities);
    }

    public Optional<CapabilityDescriptor> find(String name) {
        return list().stream()
                .filter(capability -> capability.name().equals(name))
                .findFirst();
    }

    private CapabilityDescriptor copywritingCapability() {
        return new CapabilityDescriptor(
                "copywriting",
                "Create marketing copy, group notifications, SMS snippets, and campaign-facing messages.",
                List.of("question"),
                List.of("copy_draft", "assumptions", "source_observations"),
                List.of("content.generate"),
                false,
                false,
                "low",
                "copywriting_provider",
                List.of("rule_inquiry", "activity_enroll"),
                List.of("rule_inquiry")
        );
    }
}
