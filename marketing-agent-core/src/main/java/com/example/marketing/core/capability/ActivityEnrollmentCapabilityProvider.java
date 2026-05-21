package com.example.marketing.core.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.ToolResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.observation.ActionProposal;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.operation.OperationRecord;
import com.example.marketing.core.operation.OperationStatus;
import com.example.marketing.core.operation.OperationStore;
import com.example.marketing.core.tool.FileTools;

@Service
public class ActivityEnrollmentCapabilityProvider implements CapabilityProvider {
    private static final Set<String> SUPPORTED = Set.of(
            "spreadsheet_summarize",
            "spreadsheet_query_product",
            "activity_rule_check",
            "enrollment_preview_create",
            "enrollment_execute"
    );

    private final FileTools fileTools;
    private final OperationStore operationStore;

    public ActivityEnrollmentCapabilityProvider(FileTools fileTools, OperationStore operationStore) {
        this.fileTools = fileTools;
        this.operationStore = operationStore;
    }

    @Override
    public String providerName() {
        return "activity_enrollment_provider";
    }

    @Override
    public boolean supports(CapabilityDescriptor descriptor) {
        return descriptor != null && SUPPORTED.contains(descriptor.name());
    }

    @Override
    public Observation execute(CapabilityExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        return switch (executionRequest.capability().name()) {
            case "spreadsheet_summarize" -> summarizeSpreadsheet(executionRequest);
            case "spreadsheet_query_product" -> queryProduct(executionRequest);
            case "activity_rule_check" -> checkActivityRule(executionRequest);
            case "enrollment_preview_create" -> createEnrollmentPreview(executionRequest);
            case "enrollment_execute" -> executeEnrollment(executionRequest, marketingRequest);
            default -> Observation.failed(executionRequest.runId(), executionRequest.taskNodeId(),
                    executionRequest.capability().name(), "Unsupported enrollment capability.",
                    "UNSUPPORTED_CAPABILITY", false);
        };
    }

    private Observation summarizeSpreadsheet(CapabilityExecutionRequest request) {
        String path = value(request.inputs().get("excel_file_path"));
        if (path.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.capability().name(),
                    List.of("excel_file_path"));
        }
        ToolResult result = fileTools.summarizeExcelContent(path);
        if (!result.ok()) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.capability().name(),
                    result.userSafeMessage(), result.errorCode(), result.retryable());
        }
        String summary = "已读取报名表格：" + path + "，识别到 " + result.data().getOrDefault("row_count", 0)
                + " 行数据，字段包括：" + result.data().getOrDefault("columns", List.of());
        return succeeded(request, summary, result.data(), Map.of("spreadsheet_summary", result.data()), 0.85,
                Map.of("spreadsheet_summary", result.data()));
    }

    private Observation queryProduct(CapabilityExecutionRequest request) {
        String path = value(request.inputs().get("excel_file_path"));
        String keyword = firstNonBlank(value(request.inputs().get("product_id")),
                value(request.inputs().get("product")), value(request.inputs().get("keyword")));
        if (path.isBlank() || keyword.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.capability().name(),
                    missing(path, "excel_file_path", keyword, "product_id"));
        }
        ToolResult result = fileTools.querySpreadsheetRows(path, keyword, intValue(request.inputs().get("max_rows"), 10));
        if (!result.ok()) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.capability().name(),
                    result.userSafeMessage(), result.errorCode(), result.retryable());
        }
        String summary = "已在报名表格中查询商品/关键词 " + keyword + "，匹配到 "
                + result.data().getOrDefault("matched_count", 0) + " 行。";
        return succeeded(request, summary, result.data(), Map.of("product_rows", result.data()), 0.8,
                Map.of("last_product_query", result.data()));
    }

    private Observation checkActivityRule(CapabilityExecutionRequest request) {
        String activityId = value(request.inputs().get("activity_id"));
        String source = value(request.inputs().get("source_observations"));
        if (activityId.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.capability().name(),
                    List.of("activity_id"));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("activity_id", activityId);
        evidence.put("source_observations", source);
        evidence.put("rule_check_basis", source.isBlank()
                ? "当前仅基于活动 ID 建立规则检查占位结果；真实规则证据应由 RAG/规则 provider 补齐。"
                : "基于上游 observation 中的表格/商品证据进行规则检查。");
        String summary = "已完成活动 " + activityId + " 的规则检查准备。当前没有发现阻断报名的确定性证据；"
                + "若接入真实规则库，应在此节点输出可引用规则证据。";
        return succeeded(request, summary, evidence, Map.of("rule_check", evidence), 0.55,
                Map.of("last_rule_check", evidence));
    }

    private Observation createEnrollmentPreview(CapabilityExecutionRequest request) {
        String path = value(request.inputs().get("excel_file_path"));
        String activityId = value(request.inputs().get("activity_id"));
        if (path.isBlank() || activityId.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.capability().name(),
                    missing(path, "excel_file_path", activityId, "activity_id"));
        }
        ToolResult summaryResult = fileTools.summarizeExcelContent(path);
        if (!summaryResult.ok()) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.capability().name(),
                    summaryResult.userSafeMessage(), summaryResult.errorCode(), summaryResult.retryable());
        }
        String cardId = "confirm_enroll_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "activity_enroll:" + request.conversationId() + ":" + cardId;
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("operation", "activity_enrollment");
        diff.put("activity_id", activityId);
        diff.put("excel_file_path", path);
        diff.put("detected_row_count", summaryResult.data().getOrDefault("row_count", 0));
        diff.put("columns", summaryResult.data().getOrDefault("columns", List.of()));

        Map<String, Object> cardData = new LinkedHashMap<>(request.inputs());
        cardData.put("source_agent", providerName());
        cardData.put("capability_name", "enrollment_execute");
        cardData.put("task_graph_id", request.taskGraphId());
        cardData.put("task_node_id", "");
        cardData.put("activity_id", activityId);
        cardData.put("excel_file_path", path);
        cardData.put("idempotency_key", idempotencyKey);
        cardData.put("execution_diff", diff);
        cardData.put("actions", List.of(
                Map.of("id", "confirm", "label", "确认报名", "enabled", true),
                Map.of("id", "cancel", "label", "取消", "enabled", true),
                Map.of("id", "modify", "label", "我要调整", "enabled", true)
        ));
        String summary = "已生成活动 " + activityId + " 的报名预览，识别到 "
                + summaryResult.data().getOrDefault("row_count", 0) + " 行数据。确认后才会执行报名。";
        VisibleObject card = VisibleObject.of(cardId, "hitl_confirmation", "确认活动报名", "pending",
                summary, cardData);
        ActionProposal proposal = new ActionProposal(cardId, "hitl_confirmation", "enrollment_execute",
                summary, cardData, "high");
        return new Observation(null, request.runId(), request.taskNodeId(), request.capability().name(),
                "waiting_for_approval", summary,
                Map.of("spreadsheet_summary", summaryResult.data(), "harness_boundary",
                        "preview_only_no_side_effect"),
                Map.of("enrollment_preview", diff, "idempotency_key", idempotencyKey),
                0.85, List.of(), "medium", true, proposal, "", false, List.of(card),
                List.of(ConversationMessage.assistant(summary, providerName(), "waiting_for_approval",
                        Map.of("pendingActionId", cardId, "capability", "enrollment_execute"))),
                Map.of("current_task", Map.of("type", "enrollment_preview", "status", "waiting_for_approval",
                        "task_graph_id", request.taskGraphId(), "task_node_id", request.taskNodeId())));
    }

    private Observation executeEnrollment(CapabilityExecutionRequest request, MarketingRequest marketingRequest) {
        if (!Boolean.TRUE.equals(request.inputs().get("pending_action_approved"))) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.capability().name(),
                    "报名执行没有来自 PendingActionStateMachine 的审批授权，已被 provider 拒绝。",
                    "PENDING_ACTION_APPROVAL_REQUIRED", false);
        }
        String path = value(request.inputs().get("excel_file_path"));
        String activityId = value(request.inputs().get("activity_id"));
        if (path.isBlank() || activityId.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.capability().name(),
                    missing(path, "excel_file_path", activityId, "activity_id"));
        }
        String idempotencyKey = firstNonBlank(value(request.inputs().get("idempotency_key")),
                "activity_enroll:" + request.conversationId() + ":" + request.taskGraphId() + ":" + request.taskNodeId());
        OperationRecord existing = operationStore.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null && OperationStatus.SUCCEEDED.equals(existing.status())) {
            return enrollmentExecutedObservation(request, activityId, path, existing.operationId(), idempotencyKey,
                    "这次报名已经执行过，操作号：" + existing.operationId());
        }
        String operationId = "enroll_" + activityId + "_" + UUID.randomUUID().toString().substring(0, 8);
        OperationRecord running = operationStore.start(operationId, idempotencyKey, "activity_enroll",
                Map.of("activity_id", activityId, "excel_file_path", path,
                        "pending_action_id", value(request.inputs().get("pending_action_id")),
                        "approved_by", marketingRequest.userId() == null ? "" : marketingRequest.userId()));
        operationStore.save(running.succeeded(Map.of("operation_id", operationId, "idempotency_key", idempotencyKey)));
        return enrollmentExecutedObservation(request, activityId, path, operationId, idempotencyKey,
                "已根据你的确认执行活动 " + activityId + " 的报名。操作号：" + operationId);
    }

    private Observation enrollmentExecutedObservation(CapabilityExecutionRequest request, String activityId,
                                                      String path, String operationId, String idempotencyKey,
                                                      String summary) {
        Map<String, Object> receipt = Map.of(
                "operation_id", operationId,
                "idempotency_key", idempotencyKey,
                "activity_id", activityId,
                "excel_file_path", path
        );
        return new Observation(null, request.runId(), request.taskNodeId(), request.capability().name(),
                "succeeded", summary, receipt, Map.of("operation_receipt", receipt), 0.95,
                List.of(), "high", false, null, "", false, List.of(),
                List.of(ConversationMessage.assistant(summary, providerName(), "final_answer",
                        Map.of("operationId", operationId, "capability", request.capability().name()))),
                Map.of("last_operation", receipt,
                        "current_task", Map.of("type", "enrollment_execute", "status", "succeeded")));
    }

    private Observation succeeded(CapabilityExecutionRequest request, String summary, Map<String, Object> evidence,
                                  Map<String, Object> artifacts, double confidence,
                                  Map<String, Object> statePatch) {
        return new Observation(null, request.runId(), request.taskNodeId(), request.capability().name(),
                "succeeded", summary, evidence, artifacts, confidence, List.of(), request.capability().riskLevel(),
                false, null, "", false, List.of(),
                List.of(ConversationMessage.assistant(summary, providerName(), "observation",
                        Map.of("capability", request.capability().name()))),
                statePatch);
    }

    private List<String> missing(String firstValue, String firstKey, String secondValue, String secondKey) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (firstValue == null || firstValue.isBlank()) {
            missing.add(firstKey);
        }
        if (secondValue == null || secondValue.isBlank()) {
            missing.add(secondKey);
        }
        return missing;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        }
        catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? value(second) : first;
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? value(third) : second;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
