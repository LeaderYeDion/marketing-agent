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

    public Optional<LoadedSkill> load(String skillName) {
        String resourceName = descriptors.stream()
                .filter(descriptor -> descriptor.name().equals(skillName))
                .findFirst()
                .map(SkillDescriptor::promptResource)
                .orElse("/skills/" + skillName + ".md");
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
        return readResource("/skills/" + skillName + "/skill.yaml").map(this::parseDescriptor);
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
        return new SkillDescriptor(
                name,
                values.getOrDefault("version", "0.0.0"),
                values.getOrDefault("description", ""),
                values.getOrDefault("entryAgent", ""),
                csv(values.get("requiredContext")),
                csv(values.get("canEmit")),
                csv(values.get("permissions")),
                values.getOrDefault("promptResource", "/skills/" + name + ".md"),
                csv(values.get("evalSuites"))
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

    private List<SkillDescriptor> fallbackDescriptors() {
        return List.of(
                new SkillDescriptor(
                        "activity_enroll",
                        "Handle Excel based marketing activity enrollment with HITL confirmation.",
                        "activity_enroll_agent",
                        List.of("excel_file_path", "activity_id"),
                        List.of("markdown", "status", "hitl_card", "result_card")
                ),
                new SkillDescriptor(
                        "rule_inquiry",
                        "Answer marketing activity, promotion, enrollment and rule questions.",
                        "inquiry_agent",
                        List.of("question"),
                        List.of("markdown", "citations")
                )
        );
    }
}
