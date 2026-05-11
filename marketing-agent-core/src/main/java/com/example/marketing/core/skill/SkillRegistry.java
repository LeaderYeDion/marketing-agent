package com.example.marketing.core.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class SkillRegistry {
    private final List<SkillDescriptor> descriptors = List.of(
            new SkillDescriptor(
                    "activity_enroll",
                    "处理用户基于本地 Excel 表格为指定营销活动报名优惠的诉求。",
                    "activity_enroll_agent",
                    List.of("excel_file_path", "activity_id"),
                    List.of("markdown", "status", "hitl_card", "result_card")
            ),
            new SkillDescriptor(
                    "rule_inquiry",
                    "处理营销活动、优惠报名、优惠生效、优惠或活动状态、规则解释相关咨询。",
                    "inquiry_agent",
                    List.of("question"),
                    List.of("markdown", "citations")
            )
    );

    public List<SkillDescriptor> list() {
        return descriptors;
    }

    public Optional<LoadedSkill> load(String skillName) {
        String resourceName = "/skills/" + skillName + ".md";
        try (InputStream stream = SkillRegistry.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(new LoadedSkill(skillName, new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load skill: " + skillName, ex);
        }
    }
}
