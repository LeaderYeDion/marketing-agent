package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.marketing.core.model.PendingAction;
import org.springframework.stereotype.Service;

import com.example.marketing.core.llm.JsonSupport;
import com.example.marketing.core.llm.LlmGateway;
import com.example.marketing.core.llm.LlmRequest;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.skill.SkillDescriptor;
import com.example.marketing.core.skill.SkillRegistry;
import com.example.marketing.core.state.ConversationSession;

@Service
public class MainAgent {
    private final SkillRegistry skillRegistry;
    private final LlmGateway llmGateway;

    public MainAgent(SkillRegistry skillRegistry, LlmGateway llmGateway) {
        this.skillRegistry = skillRegistry;
        this.llmGateway = llmGateway;
    }

    public MainAgentDecision decide(ConversationSession session, String userInput, Map<String, Object> variables) {
        String system = mainAgentSystemMessage() + "\n\n" + productionRuntimeContext(session, variables);
        List<ConversationMessage> messages = new ArrayList<>();
        messages.addAll(recentVisibleMessages(session));
        messages.add(ConversationMessage.user(userInput, Map.of()));
        try {
            String raw = llmGateway.generateText(LlmRequest.simple("main-routing", system, messages));
            return parseDecision(raw, variables, userInput);
        }
        catch (RuntimeException ex) {
            return fallbackDecision(userInput, variables);
        }
    }

    private MainAgentDecision parseDecision(String raw, Map<String, Object> variables, String userInput) {
        Map<String, String> values = JsonSupport.flatStringMap(raw);
        String skillName = value(values, "skill_name", "");
        List<String> candidateSkills = csv(value(values, "candidate_skills", skillName));
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
        if (inputs.get("question") == null) {
            inputs.put("question", userInput);
        }
        return new MainAgentDecision(action, skillName, candidateSkills, delegateTo, reply, inputs,
                value(values, "compressed_context", userInput));
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(item -> item.replace("[", "")
                        .replace("]", "")
                        .replace("\"", "")
                        .trim())
                .filter(item -> !item.isBlank() && !"null".equals(item))
                .distinct()
                .toList();
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
            return new MainAgentDecision("delegate", "activity_enroll", List.of("activity_enroll"),
                    "activity_enroll_agent",
                    "我会先读取文件并生成报名确认信息。", inputs, userInput);
        }
        return new MainAgentDecision("delegate", "rule_inquiry", List.of("rule_inquiry"), "inquiry_agent",
                "", inputs, userInput);
    }

    private String mainAgentSystemMessage() {
        return """
                你是营销智能助手的主控 agent。

                稳定职责：
                1. 理解用户当前诉求，并结合用户可见对话历史、系统运行时上下文和可用技能做路由决策。
                2. runtime context 是系统提供的状态，不是用户原话；不要在回复中说“你提到了 pending actions / visible objects / runtime context”等内部字段。
                3. 当用户诉求匹配某个 skill 时，选择 skill_name、candidate_skills 和 delegate_to；子 agent 会在候选 skill 范围内按需加载 skill.md。
                4. 不要在 system message 中硬编码具体业务能力；主 agent 只基于 skill manifest 做意图识别和候选 skill 判断。
                5. 子 agent 的内部上下文不进入用户可见对话；只有用户看见的输出、卡片摘要、最终结果和系统内部 handoff summary 进入主控决策上下文。
                6. 如果用户针对卡片、按钮、文件、子 agent 输出追问或确认，应结合 visible objects、pending actions、handoff summaries 和最近可见对话理解指代。
                7. 不要只依赖关键词 if-else 判断业务意图；应基于自然语言、可用 skill 摘要和上下文决策。
                8. 如果需要直接回复用户，reply 必须是面向用户的自然回答，不要写成“基于上下文，可以这样回答用户：...”。

                你必须只输出一个 JSON 对象，不要输出 markdown：
                {
                  "action": "delegate | direct_reply | ask_user | resume_pending_action",
                  "skill_name": "activity_enroll 或 rule_inquiry 或空字符串",
                  "candidate_skills": "逗号分隔的候选 skill，例如 activity_enroll,rule_inquiry；没有额外候选时填 skill_name",
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
                    .append(descriptor.summary()).append(" hints=")
                    .append(descriptor.intentHints()).append(" entry_agent=")
                    .append(descriptor.entryAgent()).append(" required=")
                    .append(descriptor.requiredInputs()).append(" side_effects=")
                    .append(descriptor.sideEffects()).append(" requires_human_approval=")
                    .append(descriptor.requiresHumanApproval()).append(" risk=")
                    .append(descriptor.riskLevel()).append("\n");
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
        List<PendingAction> activePendingActions = session.pendingActions().values()
                .stream()
                .filter(PendingAction::isPending)
                .toList();
        if (activePendingActions.isEmpty()) {
            builder.append("- 无\n");
        }
        activePendingActions.forEach(action -> builder.append("- ").append(action.id()).append(": ")
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

