package com.example.marketing.core.skill;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.example.marketing.core.agent.SubAgentWorkers;
import com.example.marketing.core.agent.SubAgentRegistry;
import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.worker.WorkerProvider;

@Component
public class SkillRegistryValidator implements InitializingBean {
    private final SkillRegistry skillRegistry;
    private final SubAgentRegistry subAgentRegistry;
    private final List<WorkerProvider> workerProviders;

    public SkillRegistryValidator(SkillRegistry skillRegistry, SubAgentRegistry subAgentRegistry,
                                  List<WorkerProvider> workerProviders) {
        this.skillRegistry = skillRegistry;
        this.subAgentRegistry = subAgentRegistry;
        this.workerProviders = workerProviders == null ? List.of() : workerProviders;
    }

    @Override
    public void afterPropertiesSet() {
        skillRegistry.list().forEach(descriptor -> {
            WorkerDescriptor worker = WorkerDescriptor.fromSkill(descriptor);
            boolean supported = workerProviders.stream().anyMatch(provider -> provider.supports(worker));
            if (!supported) {
                throw new IllegalStateException("Skill " + descriptor.name()
                        + " has no worker provider for entry: " + descriptor.entryAgent());
            }
            SubAgentWorkers workers = subAgentRegistry.workers().get(descriptor.entryAgent());
            if (workers == null) {
                validateSkillResource(descriptor);
                return;
            }
            Set<String> missingInputs = new LinkedHashSet<>(descriptor.requiredInputs());
            missingInputs.removeAll(workers.requiredInputs());
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

