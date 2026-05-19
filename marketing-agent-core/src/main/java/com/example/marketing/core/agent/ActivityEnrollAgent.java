package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.ToolResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.operation.OperationRecord;
import com.example.marketing.core.operation.OperationStatus;
import com.example.marketing.core.operation.OperationStore;
import com.example.marketing.core.skill.SkillRegistry;
import com.example.marketing.core.tool.FileTools;

@Service
public class ActivityEnrollAgent implements SubAgent {
    private final FileTools fileTools;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final OperationStore operationStore;
    private final SkillRegistry skillRegistry;

    public ActivityEnrollAgent(FileTools fileTools, ObjectProvider<ChatModel> chatModelProvider,
                               OperationStore operationStore, SkillRegistry skillRegistry) {
        this.fileTools = fileTools;
        this.chatModelProvider = chatModelProvider;
        this.operationStore = operationStore;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String name() {
        return "activity_enroll_agent";
    }

    @Override
    public SubAgentCapabilities capabilities() {
        return SubAgentCapabilities.hitlWithSideEffects(java.util.Set.of("excel_file_path", "activity_id"));
    }

    @Override
    public SubAgentResult run(SubAgentInvocation invocation, MarketingRequest request) {
        SubAgentEventSink sink = new SubAgentEventSink(invocation.conversationId(), invocation.invocationId(),
                name());
        List<ConversationMessage> commits = new ArrayList<>();
        ActivityAgentRunState runState = new ActivityAgentRunState();
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            String message = "当前没有可用的大模型配置，无法完成活动报名智能体任务。请稍后重试。";
            commits.add(ConversationMessage.assistant(message, name(), "failure", Map.of()));
            return SubAgentResult.failed(invocation.invocationId(), message, message,
                    Map.of("error_code", "CHAT_MODEL_NOT_AVAILABLE"), commits);
        }

        ActivityTools tools = new ActivityTools(invocation, request, sink, runState);
        String task = request.query() == null || request.query().isBlank() ? invocation.task() : request.query();
        try {
            AssistantMessage answer = AgenticSubAgentSupport.runReactAgent(
                    name(),
                    activityInstruction(invocation),
                    chatModel,
                    skillRegistry,
                    tools.callbacks(),
                    AgenticSubAgentSupport.toMessages(activitySystemMessage(), invocation, task, List.of()),
                    invocation.conversationId() + ":" + invocation.invocationId());
            String text = blankToDefault(answer.getText(), "我还需要更多信息才能继续处理活动报名相关问题。");

            if (isConfirmed(invocation) && !runState.executed()) {
                text = tools.executeConfirmedEnrollment(new ExecuteEnrollRequest("confirmed by orchestrator"));
            }
            if (runState.hasVisibleObjects()) {
                text = blankToDefault(text, runState.firstVisibleObjectSummary());
            }

            sink.token(text);
            commits.add(ConversationMessage.assistant(text, name(), eventType(runState),
                    Map.of("invocationId", invocation.invocationId())));
            return new SubAgentResult(invocation.invocationId(), runState.resultStatus(), text,
                    "活动报名智能体已基于技能、工具和上下文处理用户输入：" + task,
                    runState.statePatch(), runState.visibleObjects(), commits, Map.of());
        }
        catch (RuntimeException ex) {
            String message = "我在活动报名智能体推理过程中遇到错误：" + ex.getMessage();
            commits.add(ConversationMessage.assistant(message, name(), "failure",
                    Map.of("error", ex.getClass().getSimpleName())));
            return SubAgentResult.failed(invocation.invocationId(), message,
                    "活动报名智能体执行失败：" + ex.getMessage(),
                    Map.of("error_code", "AGENT_RUNTIME_ERROR"), commits);
        }
    }

    private String activityInstruction(SubAgentInvocation invocation) {
        return """
                You are an activity enrollment domain agent, not a fixed enrollment workflow.
                Understand the user's free-form request first. The user may ask to inspect an uploaded spreadsheet,
                ask about a product price in the spreadsheet, prepare an enrollment, explain missing inputs, or continue
                after human approval.

                Decide what to do:
                - If the user asks about spreadsheet content, use spreadsheet tools and answer directly.
                - If the user wants enrollment and inputs are missing, ask for the missing fields.
                - If the user wants enrollment and has activity_id plus excel_file_path, inspect the file and call
                  create_enrollment_confirmation_card. Do not execute before human approval.
                - If confirmed=true is present, call execute_confirmed_enrollment exactly once.
                - Use read_skill when the skill can clarify responsibilities, few-shots, or constraints.

                Current confirmed flag: %s
                Answer in Chinese.
                """.formatted(Boolean.TRUE.equals(invocation.inputs().get("confirmed")));
    }

    private String activitySystemMessage() {
        return """
                你是活动报名智能体。你需要能处理任何用户输入：相关、无关、模糊、查询型、执行型都可能出现。
                你拥有工具，但必须先思考用户真正想要什么。

                安全边界：
                - 读取、查询、解释 Excel 是低风险动作，可以直接做。
                - 生成报名确认卡是中风险动作，可以做，但必须等待用户确认。
                - 真正执行报名是高风险动作，只能在 confirmed=true 时通过 execute_confirmed_enrollment 工具完成。
                - 如果证据不足或缺少文件路径/活动 ID，就自然追问，不要硬编。
                """;
    }

    private String eventType(ActivityAgentRunState runState) {
        if (runState.executed()) {
            return "final_answer";
        }
        if (runState.hasVisibleObjects()) {
            return "hitl_required";
        }
        return "final_answer";
    }

    private boolean isConfirmed(SubAgentInvocation invocation) {
        return Boolean.TRUE.equals(invocation.inputs().get("confirmed"));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private static final class ActivityAgentRunState {
        private final List<VisibleObject> visibleObjects = new ArrayList<>();
        private final Map<String, Object> statePatch = new LinkedHashMap<>();
        private boolean executed;

        private void addConfirmationCard(VisibleObject card, String activityId, String excelPath, long rowCount) {
            visibleObjects.add(card);
            statePatch.put("current_task", Map.of("type", "activity_enroll", "status", "hitl_required"));
            statePatch.put("activity_context", Map.of(
                    "activity_id", activityId,
                    "excel_file_path", excelPath,
                    "detected_row_count", rowCount
            ));
        }

        private void markExecuted(String operationId, String activityId, String excelPath) {
            executed = true;
            statePatch.put("current_task", Map.of("type", "activity_enroll", "status", "succeeded"));
            statePatch.put("last_operation", Map.of("operation_id", operationId, "activity_id", activityId,
                    "excel_file_path", excelPath));
        }

        private boolean executed() {
            return executed;
        }

        private boolean hasVisibleObjects() {
            return !visibleObjects.isEmpty();
        }

        private String firstVisibleObjectSummary() {
            return visibleObjects.isEmpty() ? "" : visibleObjects.get(0).summary();
        }

        private String resultStatus() {
            return visibleObjects.stream().anyMatch(object -> "hitl_confirmation".equals(object.type()))
                    ? "hitl_required" : "succeeded";
        }

        private Map<String, Object> statePatch() {
            if (statePatch.isEmpty()) {
                statePatch.put("current_task", Map.of("type", "activity_enroll", "status",
                        hasVisibleObjects() ? "hitl_required" : "succeeded"));
            }
            return statePatch;
        }

        private List<VisibleObject> visibleObjects() {
            return visibleObjects;
        }
    }

    private final class ActivityTools {
        private final SubAgentInvocation invocation;
        private final MarketingRequest request;
        private final SubAgentEventSink sink;
        private final ActivityAgentRunState runState;

        private ActivityTools(SubAgentInvocation invocation, MarketingRequest request, SubAgentEventSink sink,
                              ActivityAgentRunState runState) {
            this.invocation = invocation;
            this.request = request;
            this.sink = sink;
            this.runState = runState;
        }

        private List<ToolCallback> callbacks() {
            return List.of(
                    FunctionToolCallback.builder("summarize_excel_content", this::summarizeExcelContent)
                            .description("Read a local xlsx/csv/txt file and return columns, row count, samples, and preview.")
                            .inputType(FileRequest.class)
                            .build(),
                    FunctionToolCallback.builder("query_spreadsheet_rows", this::querySpreadsheetRows)
                            .description("Search rows in a local spreadsheet by keyword, such as product id, SKU, name, price, or rule text.")
                            .inputType(QueryRowsRequest.class)
                            .build(),
                    FunctionToolCallback.builder("create_enrollment_confirmation_card", this::createConfirmationCard)
                            .description("Create a human approval card for activity enrollment. This does not execute enrollment.")
                            .inputType(ConfirmationRequest.class)
                            .build(),
                    FunctionToolCallback.builder("execute_confirmed_enrollment", this::executeConfirmedEnrollment)
                            .description("Execute enrollment after human approval. This tool is allowed only when confirmed=true is present.")
                            .inputType(ExecuteEnrollRequest.class)
                            .build());
        }

        private String summarizeExcelContent(FileRequest request) {
            String path = firstNonBlank(request.filePath(), stringInput(invocation, "excel_file_path"));
            sink.toolStart("summarize_excel_content", Map.of("filePath", path));
            ToolResult result = fileTools.summarizeExcelContent(path);
            sink.toolEnd("summarize_excel_content", Map.of("ok", result.ok(), "errorCode",
                    result.errorCode() == null ? "" : result.errorCode()));
            return result.ok() ? result.data().toString() : result.userSafeMessage();
        }

        private String querySpreadsheetRows(QueryRowsRequest request) {
            String path = firstNonBlank(request.filePath(), stringInput(invocation, "excel_file_path"));
            String keyword = blankToDefault(request.keyword(), "");
            sink.toolStart("query_spreadsheet_rows", Map.of("filePath", path, "keyword", keyword));
            ToolResult result = fileTools.querySpreadsheetRows(path, keyword, request.maxRows());
            sink.toolEnd("query_spreadsheet_rows", Map.of("ok", result.ok(), "errorCode",
                    result.errorCode() == null ? "" : result.errorCode()));
            return result.ok() ? result.data().toString() : result.userSafeMessage();
        }

        private String createConfirmationCard(ConfirmationRequest request) {
            String excelPath = firstNonBlank(request.filePath(), stringInput(invocation, "excel_file_path"));
            String activityId = firstNonBlank(request.activityId(), stringInput(invocation, "activity_id"));
            if (excelPath.isBlank() || activityId.isBlank()) {
                return "缺少 excel_file_path 或 activity_id，不能生成报名确认卡。请向用户追问缺失字段。";
            }
            ToolResult summary = fileTools.summarizeExcelContent(excelPath);
            if (!summary.ok()) {
                return summary.userSafeMessage();
            }
            String cardId = "confirm_enroll_" + UUID.randomUUID().toString().substring(0, 8);
            long rowCount = number(summary.data().get("row_count"));
            Object columns = summary.data().get("columns");
            String cardSummary = firstNonBlank(request.summary(), "活动 " + activityId + " 的报名确认，文件 "
                    + excelPath + "，识别到约 " + rowCount + " 行数据。");
            Map<String, Object> cardData = new LinkedHashMap<>();
            cardData.put("source_agent", name());
            cardData.put("skill_name", invocation.skillName());
            cardData.put("activity_id", activityId);
            cardData.put("excel_file_path", excelPath);
            cardData.put("detected_row_count", rowCount);
            cardData.put("columns", columns);
            cardData.put("agent_rationale", blankToDefault(request.rationale(), ""));
            cardData.put("actions", List.of(
                    Map.of("id", "confirm", "label", "确认报名", "enabled", true),
                    Map.of("id", "cancel", "label", "取消", "enabled", true),
                    Map.of("id", "modify", "label", "我要调整", "enabled", true)
            ));
            VisibleObject card = VisibleObject.of(cardId, "hitl_confirmation", "确认活动报名", "pending",
                    cardSummary, cardData);
            runState.addConfirmationCard(card, activityId, excelPath, rowCount);
            sink.card("已生成报名确认卡片。", Map.of("card", card));
            sink.hitlRequired("请确认是否执行活动报名。", Map.of("cardId", cardId));
            return "已生成确认卡：" + cardId + "。等待用户确认后才能执行报名。";
        }

        private String executeConfirmedEnrollment(ExecuteEnrollRequest request) {
            if (!isConfirmed(invocation)) {
                return "当前没有 confirmed=true，不能执行报名。请先生成确认卡并等待用户确认。";
            }
            String excelPath = stringInput(invocation, "excel_file_path");
            String activityId = stringInput(invocation, "activity_id");
            String operationId = "enroll_" + activityId + "_" + invocation.invocationId();
            String idempotencyKey = "activity_enroll:" + invocation.conversationId() + ":"
                    + invocation.inputs().getOrDefault("visible_object_id", invocation.invocationId());
            OperationRecord existing = operationStore.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null && OperationStatus.SUCCEEDED.equals(existing.status())) {
                runState.markExecuted(existing.operationId(), activityId, excelPath);
                return "这次报名已经执行过，操作号：" + existing.operationId();
            }
            OperationRecord running = operationStore.start(operationId, idempotencyKey, "activity_enroll",
                    Map.of("activity_id", activityId, "excel_file_path", excelPath,
                            "reason", request == null ? "" : blankToDefault(request.reason(), "")));
            operationStore.save(running.succeeded(Map.of("operation_id", operationId)));
            runState.markExecuted(operationId, activityId, excelPath);
            return "已根据你的确认执行报名。当前实现生成了幂等操作号 " + operationId + "，后续可在这里接入真实报名接口。";
        }

        private String firstNonBlank(String first, String second) {
            return first == null || first.isBlank() ? blankToDefault(second, "") : first;
        }
    }

    private record FileRequest(String filePath, String reason) {
    }

    private record QueryRowsRequest(String filePath, String keyword, int maxRows, String reason) {
    }

    private record ConfirmationRequest(String filePath, String activityId, String summary, String rationale) {
    }

    private record ExecuteEnrollRequest(String reason) {
    }
}
