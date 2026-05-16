package com.example.marketing.core.skill;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.example.marketing.core.agent.SubAgentCapabilities;
import com.example.marketing.core.agent.SubAgentRegistry;

@Component
public class SkillRegistryValidator implements InitializingBean {
    private final SkillRegistry skillRegistry;
    private final SubAgentRegistry subAgentRegistry;

    public SkillRegistryValidator(SkillRegistry skillRegistry, SubAgentRegistry subAgentRegistry) {
        this.skillRegistry = skillRegistry;
        this.subAgentRegistry = subAgentRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        skillRegistry.list().forEach(descriptor -> {
            SubAgentCapabilities capabilities = subAgentRegistry.capabilities().get(descriptor.entryAgent());
            if (capabilities == null) {
                throw new IllegalStateException("Skill " + descriptor.name()
                        + " references unknown entry agent: " + descriptor.entryAgent());
            }
            Set<String> missingInputs = new LinkedHashSet<>(descriptor.requiredContext());
            missingInputs.removeAll(capabilities.requiredInputs());
            if (!missingInputs.isEmpty()) {
                throw new IllegalStateException("Skill " + descriptor.name()
                        + " requires inputs not supported by " + descriptor.entryAgent() + ": " + missingInputs);
            }
        });
    }
}
