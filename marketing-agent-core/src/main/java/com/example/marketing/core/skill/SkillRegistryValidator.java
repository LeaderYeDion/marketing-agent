package com.example.marketing.core.skill;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.example.marketing.core.agent.SubAgentCapabilities;
import com.example.marketing.core.agent.SubAgentRegistry;
import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.capability.CapabilityProvider;

@Component
public class SkillRegistryValidator implements InitializingBean {
    private final SkillRegistry skillRegistry;
    private final SubAgentRegistry subAgentRegistry;
    private final List<CapabilityProvider> capabilityProviders;

    public SkillRegistryValidator(SkillRegistry skillRegistry, SubAgentRegistry subAgentRegistry,
                                  List<CapabilityProvider> capabilityProviders) {
        this.skillRegistry = skillRegistry;
        this.subAgentRegistry = subAgentRegistry;
        this.capabilityProviders = capabilityProviders == null ? List.of() : capabilityProviders;
    }

    @Override
    public void afterPropertiesSet() {
        skillRegistry.list().forEach(descriptor -> {
            CapabilityDescriptor capability = CapabilityDescriptor.fromSkill(descriptor);
            boolean supported = capabilityProviders.stream().anyMatch(provider -> provider.supports(capability));
            if (!supported) {
                throw new IllegalStateException("Skill " + descriptor.name()
                        + " has no capability provider for entry: " + descriptor.entryAgent());
            }
            SubAgentCapabilities capabilities = subAgentRegistry.capabilities().get(descriptor.entryAgent());
            if (capabilities == null) {
                validateSkillResource(descriptor);
                return;
            }
            Set<String> missingInputs = new LinkedHashSet<>(descriptor.requiredInputs());
            missingInputs.removeAll(capabilities.requiredInputs());
            if (!missingInputs.isEmpty()) {
                throw new IllegalStateException("Skill " + descriptor.name()
                        + " requires inputs not supported by " + descriptor.entryAgent() + ": " + missingInputs);
            }
            validateSkillResource(descriptor);
        });
    }

    private void validateSkillResource(SkillDescriptor descriptor) {
        if (skillRegistry.load(descriptor.name()).isEmpty()) {
            throw new IllegalStateException("Skill " + descriptor.name()
                    + " is missing skill resource: " + descriptor.skillResource());
        }
    }
}
