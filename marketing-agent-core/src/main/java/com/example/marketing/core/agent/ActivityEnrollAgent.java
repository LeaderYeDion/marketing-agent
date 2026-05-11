package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.core.llm.LlmClient;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.ToolResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.tool.FileTools;

@Service
public class ActivityEnrollAgent {
    private final FileTools fileTools;
    private final LlmClient llmClient;

    public ActivityEnrollAgent(FileTools fileTools, LlmClient llmClient) {
        this.fileTools = fileTools;
        this.llmClient = llmClient;
    }

    public SubAgentResult run(SubAgentInvocation invocation) {
        SubAgentEventSink sink = new SubAgentEventSink(invocation.conversationId(), invocation.invocationId(),
                "activity_enroll_agent");
        String excelPath = stringInput(invocation, "excel_file_path");
        String activityId = stringInput(invocation, "activity_id");
        List<ConversationMessage> commits = new ArrayList<>();

        if (isConfirmed(invocation)) {
            return executeConfirmed(invocation, sink, excelPath, activityId, commits);
        }

        sink.toolStart("summarize_excel_content", Map.of("filePath", excelPath));
        ToolResult summary = fileTools.summarizeExcelContent(excelPath);
        sink.toolEnd("summarize_excel_content", Map.of("ok", summary.ok(), "errorCode", summary.errorCode()));
        if (!summary.ok()) {
            String message = summary.userSafeMessage();
            sink.token(message);
            commits.add(ConversationMessage.assistant(message, "activity_enroll_agent", "failure",
                    Map.of("errorCode", summary.errorCode())));
            return SubAgentResult.failed(invocation.invocationId(), message,
                    "优惠报名子任务失败：" + message,
                    Map.of("stage", "summarize_excel_content", "error_code", summary.errorCode(),
                            "retryable", summary.retryable()),
                    commits);
        }

        String plan = generatePlan(invocation, activityId, summary);
        sink.token(plan);
        commits.add(ConversationMessage.assistant(plan, "activity_enroll_agent", "sub_agent_visible_update",
                Map.of("invocationId", invocation.invocationId())));

        String cardId = "confirm_enroll_" + UUID.randomUUID().toString().substring(0, 8);
        long rowCount = number(summary.data().get("row_count"));
        Object columns = summary.data().get("columns");
        String cardSummary = "活动 " + activityId + " 的优惠报名确认，文件 " + excelPath + "，识别到约 "
                + rowCount + " 行数据。";
        Map<String, Object> cardData = new LinkedHashMap<>();
        cardData.put("activity_id", activityId);
        cardData.put("excel_file_path", excelPath);
        cardData.put("detected_row_count", rowCount);
        cardData.put("columns", columns);
        cardData.put("actions", List.of(
                Map.of("id", "confirm", "label", "确认报名", "enabled", true),
                Map.of("id", "cancel", "label", "取消", "enabled", true),
                Map.of("id", "modify", "label", "我要调整", "enabled", true)
        ));
        VisibleObject card = VisibleObject.of(cardId, "hitl_confirmation", "确认优惠报名", "pending",
                cardSummary, cardData);
        sink.card("已生成报名确认卡片。", Map.of("card", card));
        sink.hitlRequired("请确认是否执行优惠报名。", Map.of("cardId", cardId));
        commits.add(ConversationMessage.assistant("已展示确认卡片：" + cardSummary, "activity_enroll_agent",
                "card_summary", Map.of("visibleObjectId", cardId)));

        Map<String, Object> statePatch = new LinkedHashMap<>();
        statePatch.put("current_task", Map.of("type", "activity_enroll", "status", "hitl_required"));
        statePatch.put("activity_context", Map.of(
                "activity_id", activityId,
                "excel_file_path", excelPath,
                "detected_row_count", rowCount
        ));
        return new SubAgentResult(invocation.invocationId(), "hitl_required", cardSummary,
                "优惠报名子任务已读取文件并生成确认卡片 " + cardId + "，等待用户确认。", statePatch,
                List.of(card), commits,
                Map.of());
    }

    private SubAgentResult executeConfirmed(SubAgentInvocation invocation, SubAgentEventSink sink, String excelPath,
                                            String activityId, List<ConversationMessage> commits) {
        String operationId = "enroll_" + activityId + "_" + invocation.invocationId();
        String message = "已根据你的确认执行报名。当前实现生成了幂等操作号 " + operationId
                + "，后续可在这里接入真实报名接口。";
        sink.token(message);
        commits.add(ConversationMessage.assistant(message, "activity_enroll_agent", "final_answer",
                Map.of("operationId", operationId)));
        Map<String, Object> statePatch = Map.of(
                "current_task", Map.of("type", "activity_enroll", "status", "succeeded"),
                "last_operation", Map.of("operation_id", operationId, "activity_id", activityId,
                        "excel_file_path", excelPath)
        );
        return new SubAgentResult(invocation.invocationId(), "succeeded", message,
                "用户确认后已执行优惠报名，操作号：" + operationId, statePatch, List.of(), commits, Map.of());
    }

    private String generatePlan(SubAgentInvocation invocation, String activityId, ToolResult summary) {
        String system = """
                你是优惠报名子 agent。你必须基于工具返回的真实 Excel 摘要向用户解释你看到了什么、哪些列和报名相关、哪些列可能无关、你计划如何生成确认卡片。
                不要声称已经报名；当前阶段只做读取、理解和报名预览。回答用中文，简洁但要体现你真的看过列名和样例。
                """;
        String prompt = """
                活动 ID：%s
                主 agent 提炼的上下文：%s
                Excel 摘要：%s
                """.formatted(activityId, invocation.compressedContext(), summary.data());
        try {
            return llmClient.generate(system, List.of(ConversationMessage.user(prompt, Map.of())));
        }
        catch (RuntimeException ex) {
            return "我已读取文件摘要。表格包含列：" + summary.data().get("columns")
                    + "。我会优先关注活动、优惠、门槛、折扣、库存、生效时间等与报名相关的字段，并在执行前生成确认卡片。";
        }
    }

    private boolean isConfirmed(SubAgentInvocation invocation) {
        return Boolean.TRUE.equals(invocation.inputs().get("confirmed"));
    }

    private String stringInput(SubAgentInvocation invocation, String key) {
        Object value = invocation.inputs().get(key);
        return value == null ? "" : value.toString();
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }
}
