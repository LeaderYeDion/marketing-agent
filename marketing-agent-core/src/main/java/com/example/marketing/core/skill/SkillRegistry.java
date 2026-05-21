package com.example.marketing.core.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class SkillRegistry {
    private final List<SkillDescriptor> descriptors = loadDescriptors();

    public List<SkillDescriptor> list() {
        return descriptors;
    }

    public Optional<SkillDescriptor> find(String skillName) {
        return descriptors.stream()
                .filter(descriptor -> descriptor.name().equals(skillName))
                .findFirst();
    }

    public Optional<LoadedSkill> load(String skillName) {
        String resourceName = find(skillName)
                .map(SkillDescriptor::skillResource)
                .orElse("/skills/" + skillName + "/skill.md");
        return readResource(resourceName).map(content -> new LoadedSkill(skillName, content));
    }

    private List<SkillDescriptor> loadDescriptors() {
        List<String> skillNames = readResource("/skills/index.txt")
                .map(content -> content.lines()
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .toList())
                .orElse(List.of());
        List<SkillDescriptor> loaded = skillNames.stream()
                .map(this::loadDescriptor)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        return loaded.isEmpty() ? fallbackDescriptors() : loaded;
    }

    private Optional<SkillDescriptor> loadDescriptor(String skillName) {
        return readResource("/skills/" + skillName + "/manifest.yaml").map(this::parseDescriptor);
    }

    private SkillDescriptor parseDescriptor(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains(":"))
                .forEach(line -> {
                    int colon = line.indexOf(':');
                    values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                });
        String name = values.getOrDefault("name", "");
        String summary = values.getOrDefault("summary", values.getOrDefault("description", ""));
        return new SkillDescriptor(
                name,
                values.getOrDefault("version", "0.0.0"),
                summary,
                csv(values.get("intentHints")),
                values.getOrDefault("entryAgent", ""),
                csv(values.getOrDefault("requiredInputs", values.get("requiredContext"))),
                csv(values.get("canEmit")),
                csv(values.get("permissions")),
                bool(values.get("sideEffects")),
                bool(values.get("requiresHumanApproval")),
                values.getOrDefault("riskLevel", "medium"),
                values.getOrDefault("skillResource",
                        values.getOrDefault("promptResource", "/skills/" + name + "/skill.md")),
                csv(values.get("evalSuites")),
                csv(values.getOrDefault("outputContract", values.get("canEmit"))),
                csv(values.get("composableWith")),
                csv(values.get("fallbackSkills")),
                csv(values.get("preconditions")),
                csv(values.get("postconditions")),
                values.getOrDefault("owner", ""),
                values.getOrDefault("capabilityType", "")
        );
    }

    private Optional<String> readResource(String resourceName) {
        try (InputStream stream = SkillRegistry.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load skill resource: " + resourceName, ex);
        }
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private boolean bool(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private List<SkillDescriptor> fallbackDescriptors() {
        return List.of(
                new SkillDescriptor(
                        "rule_inquiry",
                        "Answer marketing activity, promotion, enrollment and rule questions.",
                        "rule_inquiry_provider",
                        List.of("question"),
                        List.of("markdown", "citations")
                ),
                new SkillDescriptor(
                        "copywriting",
                        "Create channel-aware marketing copy from user goals, audience context, and upstream observations.",
                        "copywriting_provider",
                        List.of("question"),
                        List.of("markdown", "draft_copy")
                )
        );
    }
}
