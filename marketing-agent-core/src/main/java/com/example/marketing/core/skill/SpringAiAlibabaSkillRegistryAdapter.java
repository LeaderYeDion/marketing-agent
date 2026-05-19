package com.example.marketing.core.skill;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;

public class SpringAiAlibabaSkillRegistryAdapter implements com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry {
    private final SkillRegistry delegate;

    public SpringAiAlibabaSkillRegistryAdapter(SkillRegistry delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<SkillMetadata> get(String skillName) {
        return delegate.find(skillName).map(this::toMetadata);
    }

    @Override
    public List<SkillMetadata> listAll() {
        return delegate.list().stream().map(this::toMetadata).toList();
    }

    @Override
    public boolean contains(String skillName) {
        return delegate.find(skillName).isPresent();
    }

    @Override
    public int size() {
        return delegate.list().size();
    }

    @Override
    public void reload() {
    }

    @Override
    public String readSkillContent(String skillName) throws IOException {
        return delegate.load(skillName).map(LoadedSkill::content)
                .orElseThrow(() -> new IOException("Unknown skill: " + skillName));
    }

    @Override
    public String getSkillLoadInstructions() {
        StringBuilder builder = new StringBuilder("""
                You can use skills as compact operating playbooks.
                First inspect the available skill names below, then call read_skill when a skill can help.
                Do not assume a skill's full behavior until you have read it.

                Available skills:
                """);
        for (SkillDescriptor descriptor : delegate.list()) {
            builder.append("- ").append(descriptor.name())
                    .append(": ").append(descriptor.summary())
                    .append(" (entryAgent=").append(descriptor.entryAgent())
                    .append(", riskLevel=").append(descriptor.riskLevel())
                    .append(", requiresHumanApproval=").append(descriptor.requiresHumanApproval())
                    .append(")\n");
        }
        return builder.toString();
    }

    @Override
    public String getRegistryType() {
        return "marketing-classpath";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return new SystemPromptTemplate(getSkillLoadInstructions());
    }

    private SkillMetadata toMetadata(SkillDescriptor descriptor) {
        return SkillMetadata.builder()
                .name(descriptor.name())
                .description(descriptor.summary())
                .skillPath(descriptor.skillResource())
                .source(getRegistryType())
                .fullContent(delegate.load(descriptor.name()).map(LoadedSkill::content).orElse(""))
                .build();
    }
}
