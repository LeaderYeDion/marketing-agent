package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.llm.JsonSupport;
import com.example.marketing.core.llm.LlmClient;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.skill.LoadedSkill;
import com.example.marketing.core.skill.SkillDescriptor;
import com.example.marketing.core.skill.SkillRegistry;
import com.example.marketing.core.state.ConversationSession;

@Service
public class MainAgent {
    private final SkillRegistry skillRegistry;
    private final LlmClient llmClient;

    public MainAgent(SkillRegistry skillRegistry, LlmClient llmClient) {
        this.skillRegistry = skillRegistry;
        this.llmClient = llmClient;
    }

    public MainAgentDecision decide(ConversationSession session, String userInput, Map<String, Object> variables) {
        String system = mainAgentSystemMessage() + "\n\n" + productionRuntimeContext(session, variables);
        List<ConversationMessage> messages = new ArrayList<>();
        messages.addAll(recentVisibleMessages(session));
        messages.add(ConversationMessage.user(userInput, Map.of()));
        try {
            String raw = llmClient.generate(system, messages);
            return parseDecision(raw, variables, userInput);
        }
        catch (RuntimeException ex) {
            return fallbackDecision(userInput, variables);
        }
    }

    private MainAgentDecision parseDecision(String raw, Map<String, Object> variables, String userInput) {
        Map<String, String> values = JsonSupport.flatStringMap(raw);
        String skillName = value(values, "skill_name", "");
        LoadedSkill loadedSkill = null;
        if (!skillName.isBlank()) {
            loadedSkill = skillRegistry.load(skillName).orElse(null);
        }
        String delegateTo = value(values, "delegate_to", "");
        String action = value(values, "action", delegateTo.isBlank() ? "direct_reply" : "delegate");
        String reply = value(values, "reply", "");
        Map<String, Object> inputs = new LinkedHashMap<>();
        copyIfPresent(values, inputs, "excel_file_path");
        copyIfPresent(values, inputs, "activity_id");
        copyIfPresent(values, inputs, "question");
        if (variables != null) {
            inputs.putAll(variables);
        }
        if (loadedSkill != null) {
            inputs.put("loaded_skill", loadedSkill.content());
        }
        if (inputs.get("question") == null) {
            inputs.put("question", userInput);
        }
        return new MainAgentDecision(action, skillName, delegateTo, reply, inputs,
                value(values, "compressed_context", userInput));
    }

    private void copyIfPresent(Map<String, String> source, Map<String, Object> target, String key) {
        String value = source.get(key);
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            target.put(key, value);
        }
    }

    private String value(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() || "null".equals(value) ? defaultValue : value;
    }

    private MainAgentDecision fallbackDecision(String userInput, Map<String, Object> variables) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (variables != null) {
            inputs.putAll(variables);
        }
        inputs.put("question", userInput);
        if (inputs.containsKey("excel_file_path") && inputs.containsKey("activity_id")) {
            return new MainAgentDecision("delegate", "activity_enroll", "activity_enroll_agent",
                    "我会先读取文件并生成报名确认信息。", inputs, userInput);
        }
        return new MainAgentDecision("delegate", "rule_inquiry", "inquiry_agent",
                "", inputs, userInput);
    }

    private String mainAgentSystemMessage() {
        return """
                你是营销智能助手的主控 agent。

                稳定职责：
                1. 理解用户当前诉求，并结合用户可见对话历史、系统运行时上下文和可用技能做路由决策。
                2. runtime context 是系统提供的状态，不是用户原话；不要在回复中说“你提到了 pending actions / visible objects / runtime context”等内部字段。
                3. 当用户诉求匹配某个 skill 时，选择 skill_name 和 delegate_to；系统会按需加载 skill markdown 并提供给对应子 agent。
                4. 不要在 system message 中硬编码具体业务能力；具体能力来自 skill registry 和 skill.md。
                5. 子 agent 的内部上下文不进入用户可见对话；只有用户看见的输出、卡片摘要、最终结果和系统内部 handoff summary 进入主控决策上下文。
                6. 如果用户针对卡片、按钮、文件、子 agent 输出追问或确认，应结合 visible objects、pending actions、handoff summaries 和最近可见对话理解指代。
                7. 不要只依赖关键词 if-else 判断业务意图；应基于自然语言、可用 skill 摘要和上下文决策。
                8. 如果需要直接回复用户，reply 必须是面向用户的自然回答，不要写成“基于上下文，可以这样回答用户：...”。

                你必须只输出一个 JSON 对象，不要输出 markdown：
                {
                  "action": "delegate | direct_reply | ask_user | resume_pending_action",
                  "skill_name": "activity_enroll 或 rule_inquiry 或空字符串",
                  "delegate_to": "activity_enroll_agent 或 inquiry_agent 或空字符串",
                  "reply": "需要直接给用户的简短回复；委派时可为空",
                  "excel_file_path": "如有则填写",
                  "activity_id": "如有则填写",
                  "question": "咨询问题或用户原始问题",
                  "compressed_context": "给子 agent 的压缩上下文"
                }
                """;
    }

    private String productionRuntimeContext(ConversationSession session, Map<String, Object> variables) {
        StringBuilder builder = new StringBuilder();
        builder.append("运行时上下文（系统提供，不是用户原话）：\n");
        builder.append("- 这些信息只用于判断 action、skill_name、delegate_to、inputs 和 reply。\n");
        builder.append("- 不要告诉用户“你提到了 pending actions / visible objects / runtime context”等内部字段。\n");
        builder.append("- 如果需要回复用户，请直接面向用户回答，不要描述“正在基于上下文回答”。\n");
        builder.append("\n可用 skills：\n");
        for (SkillDescriptor descriptor : skillRegistry.list()) {
            builder.append("- ").append(descriptor.name()).append(": ")
                    .append(descriptor.description()).append(" entry_agent=")
                    .append(descriptor.entryAgent()).append(" required=")
                    .append(descriptor.requiredContext()).append("\n");
        }
        builder.append("\n会话状态：\n").append(session.state()).append("\n");
        builder.append("\n用户可见对象摘要：\n");
        if (session.visibleObjects().isEmpty()) {
            builder.append("- 无\n");
        }
        session.visibleObjects().values().forEach(object -> builder.append("- ")
                .append(object.id()).append(": ").append(object.type()).append(", ")
                .append(object.status()).append(", ").append(object.summary()).append("\n"));
        builder.append("\n待确认动作：\n");
        if (session.pendingActions().isEmpty()) {
            builder.append("- 无\n");
        }
        session.pendingActions().forEach((id, action) -> builder.append("- ").append(id).append(": ")
                .append(action).append("\n"));
        appendHandoffSummaries(builder, session);
        builder.append("\n请求变量：").append(variables == null ? Map.of() : variables).append("\n");
        return builder.toString();
    }

    private void appendHandoffSummaries(StringBuilder builder, ConversationSession session) {
        List<ContextSummary> summaries = session.handoffSummaries();
        builder.append("\n子 agent 交接摘要：\n");
        if (summaries.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        summaries.stream().skip(Math.max(0, summaries.size() - 8))
                .forEach(summary -> builder.append("- source=").append(summary.source())
                        .append(" invocationId=").append(summary.invocationId())
                        .append(" status=").append(summary.status())
                        .append(" metadata=").append(summary.metadata()).append(": ")
                        .append(summary.content()).append("\n"));
    }

    private List<ConversationMessage> recentVisibleMessages(ConversationSession session) {
        List<ConversationMessage> visibleMessages = session.messages().stream()
                .filter(ConversationMessage::visible)
                .toList();
        return visibleMessages.stream()
                .skip(Math.max(0, visibleMessages.size() - 12))
                .toList();
    }
}

