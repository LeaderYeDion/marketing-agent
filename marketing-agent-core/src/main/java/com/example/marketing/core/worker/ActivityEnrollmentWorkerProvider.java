package com.example.marketing.core.worker;

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
public class ActivityEnrollmentWorkerProvider implements WorkerProvider {
    private static final Set<String> SUPPORTED = Set.of(
            "spreadsheet_summarize",
            "spreadsheet_query_product",
            "activity_rule_check",
            "enrollment_preview_create",
            "enrollment_execute"
    );

    private final FileTools fileTools;
    private final OperationStore operationStore;

    public ActivityEnrollmentWorkerProvider(FileTools fileTools, OperationStore operationStore) {
        this.fileTools = fileTools;
        this.operationStore = operationStore;
    }

    @Override
    public String providerName() {
        return "activity_enrollment_provider";
    }

    @Override
    public boolean supports(WorkerDescriptor descriptor) {
        return descriptor != null && SUPPORTED.contains(descriptor.name());
    }

    @Override
    public Observation execute(WorkerExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        return switch (executionRequest.worker().name()) {
            case "spreadsheet_summarize" -> summarizeSpreadsheet(executionRequest);
            case "spreadsheet_query_product" -> queryProduct(executionRequest);
            case "activity_rule_check" -> checkActivityRule(executionRequest);
            case "enrollment_preview_create" -> createEnrollmentPreview(executionRequest);
            case "enrollment_execute" -> executeEnrollment(executionRequest, marketingRequest);
            default -> Observation.failed(executionRequest.runId(), executionRequest.taskNodeId(),
                    executionRequest.worker().name(), "Unsupported enrollment worker.",
                    "UNSUPPORTED_CAPABILITY", false);
        };
    }

    private Observation summarizeSpreadsheet(WorkerExecutionRequest request) {
        String path = value(request.inputs().get("excel_file_path"));
        if (path.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.worker().name(),
                    List.of("excel_file_path"));
        }
        ToolResult result = fileTools.summarizeExcelContent(path);
        if (!result.ok()) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.worker().name(),
                    result.userSafeMessage(), result.errorCode(), result.retryable());
        }
        String summary = "Summarized spreadsheet " + path + " with " + result.data().getOrDefault("row_count", 0) + " rows.";
        return succeeded(request, summary, result.data(), Map.of("spreadsheet_summary", result.data()), 0.85,
                Map.of("spreadsheet_summary", result.data()));
    }

    private Observation queryProduct(WorkerExecutionRequest request) {
        String path = value(request.inputs().get("excel_file_path"));
        String keyword = firstNonBlank(value(request.inputs().get("product_id")),
                value(request.inputs().get("product")), value(request.inputs().get("keyword")));
        if (path.isBlank() || keyword.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.worker().name(),
                    missing(path, "excel_file_path", keyword, "product_id"));
        }
        ToolResult result = fileTools.querySpreadsheetRows(path, keyword, intValue(request.inputs().get("max_rows"), 10));
        if (!result.ok()) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.worker().name(),
                    result.userSafeMessage(), result.errorCode(), result.retryable());
        }
        String summary = "Found " + result.data().getOrDefault("matched_count", 0) + " rows matching product keyword: " + keyword;
        return succeeded(request, summary, result.data(), Map.of("product_rows", result.data()), 0.8,
                Map.of("last_product_query", result.data()));
    }

    private Observation checkActivityRule(WorkerExecutionRequest request) {
        String activityId = value(request.inputs().get("activity_id"));
        String source = value(request.inputs().get("source_observations"));
        if (activityId.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.worker().name(),
                    List.of("activity_id"));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("activity_id", activityId);
        evidence.put("source_observations", source);
        evidence.put("rule_check_basis", source.isBlank() ? "No source observations; checked by activity id only." : "Checked from upstream observations.");
        String summary = "Activity " + activityId + " rule check completed with available evidence.";
        return succeeded(request, summary, evidence, Map.of("rule_check", evidence), 0.55,
                Map.of("last_rule_check", evidence));
    }

    private Observation createEnrollmentPreview(WorkerExecutionRequest request) {
        String path = value(request.inputs().get("excel_file_path"));
        String activityId = value(request.inputs().get("activity_id"));
        if (path.isBlank() || activityId.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.worker().name(),
                    missing(path, "excel_file_path", activityId, "activity_id"));
        }
        ToolResult summaryResult = fileTools.summarizeExcelContent(path);
        if (!summaryResult.ok()) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.worker().name(),
                    summaryResult.userSafeMessage(), summaryResult.errorCode(), summaryResult.retryable());
        }
        String cardId = "confirm_enroll_" + UUID.randomUUID().toString().substring(0, 8);
        String executionNodeId = request.taskNodeId() + "_approved_execution";
        String idempotencyKey = "activity_enroll:" + request.conversationId() + ":" + request.taskGraphId()
                + ":" + executionNodeId;
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("operation", "activity_enrollment");
        diff.put("activity_id", activityId);
        diff.put("excel_file_path", path);
        diff.put("detected_row_count", summaryResult.data().getOrDefault("row_count", 0));
        diff.put("columns", summaryResult.data().getOrDefault("columns", List.of()));

        Map<String, Object> cardData = new LinkedHashMap<>(request.inputs());
        cardData.put("source_agent", providerName());
        cardData.put("worker_name", "enrollment_execute");
        cardData.put("task_graph_id", request.taskGraphId());
        cardData.put("task_node_id", executionNodeId);
        cardData.put("activity_id", activityId);
        cardData.put("excel_file_path", path);
        cardData.put("idempotency_key", idempotencyKey);
        cardData.put("approval_source", "enrollment_preview_create");
        cardData.put("execution_node_descriptor", Map.of(
                "id", executionNodeId,
                "worker_name", "enrollment_execute",
                "depends_on", List.of(request.taskNodeId()),
                "resume_from_observation_id", ""
        ));
        cardData.put("execution_diff", diff);
        cardData.put("actions", List.of(
                Map.of("id", "confirm", "label", "Confirm", "enabled", true),
                Map.of("id", "cancel", "label", "Cancel", "enabled", true),
                Map.of("id", "modify", "label", "Modify", "enabled", true)
        ));
        String summary = "Enrollment preview for activity " + activityId + " covers " + summaryResult.data().getOrDefault("row_count", 0) + " rows and is waiting for approval.";
        VisibleObject card = VisibleObject.of(cardId, "hitl_confirmation", "Approve enrollment preview", "pending",
                summary, cardData);
        ActionProposal proposal = new ActionProposal(cardId, "hitl_confirmation", "enrollment_execute",
                summary, cardData, "high");
        return new Observation(null, request.runId(), request.taskNodeId(), request.worker().name(),
                "waiting_for_approval", summary,
                Map.of("spreadsheet_summary", summaryResult.data(), "harness_boundary",
                        "preview_only_no_side_effect"),
                Map.of("enrollment_preview", diff, "idempotency_key", idempotencyKey),
                0.85, List.of(), "medium", true, proposal, "", false, List.of(card),
                List.of(ConversationMessage.assistant(summary, providerName(), "waiting_for_approval",
                        Map.of("pendingActionId", cardId, "worker", "enrollment_execute"))),
                Map.of("current_task", Map.of("type", "enrollment_preview", "status", "waiting_for_approval",
                        "task_graph_id", request.taskGraphId(), "task_node_id", request.taskNodeId())));
    }

    private Observation executeEnrollment(WorkerExecutionRequest request, MarketingRequest marketingRequest) {
        if (!Boolean.TRUE.equals(request.inputs().get("pending_action_approved"))) {
            return Observation.failed(request.runId(), request.taskNodeId(), request.worker().name(),
                    "Pending action approval is required before this worker can execute.",
                    "PENDING_ACTION_APPROVAL_REQUIRED", false);
        }
        String path = value(request.inputs().get("excel_file_path"));
        String activityId = value(request.inputs().get("activity_id"));
        if (path.isBlank() || activityId.isBlank()) {
            return Observation.missingInputs(request.runId(), request.taskNodeId(), request.worker().name(),
                    missing(path, "excel_file_path", activityId, "activity_id"));
        }
        String idempotencyKey = firstNonBlank(value(request.inputs().get("idempotency_key")),
                "activity_enroll:" + request.conversationId() + ":" + request.taskGraphId() + ":" + request.taskNodeId());
        OperationRecord existing = operationStore.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null && OperationStatus.SUCCEEDED.equals(existing.status())) {
            return enrollmentExecutedObservation(request, activityId, path, existing.operationId(), idempotencyKey,
                    "杩欐鎶ュ悕宸茬粡鎵ц杩囷紝鎿嶄綔鍙凤細" + existing.operationId());
        }
        String operationId = "enroll_" + activityId + "_" + UUID.randomUUID().toString().substring(0, 8);
        OperationRecord running = operationStore.start(operationId, idempotencyKey, "activity_enroll",
                Map.of("activity_id", activityId, "excel_file_path", path,
                        "pending_action_id", value(request.inputs().get("pending_action_id")),
                        "approved_by", marketingRequest.userId() == null ? "" : marketingRequest.userId()));
        operationStore.save(running.succeeded(Map.of("operation_id", operationId, "idempotency_key", idempotencyKey)));
        return enrollmentExecutedObservation(request, activityId, path, operationId, idempotencyKey,
                "Enrollment executed for activity " + activityId + ". Operation id: " + operationId);
    }

    private Observation enrollmentExecutedObservation(WorkerExecutionRequest request, String activityId,
                                                      String path, String operationId, String idempotencyKey,
                                                      String summary) {
        Map<String, Object> receipt = Map.of(
                "operation_id", operationId,
                "idempotency_key", idempotencyKey,
                "activity_id", activityId,
                "excel_file_path", path
        );
        return new Observation(null, request.runId(), request.taskNodeId(), request.worker().name(),
                "succeeded", summary, receipt, Map.of("operation_receipt", receipt), 0.95,
                List.of(), "high", false, null, "", false, List.of(),
                List.of(ConversationMessage.assistant(summary, providerName(), "final_answer",
                        Map.of("operationId", operationId, "worker", request.worker().name()))),
                Map.of("last_operation", receipt,
                        "current_task", Map.of("type", "enrollment_execute", "status", "succeeded")));
    }

    private Observation succeeded(WorkerExecutionRequest request, String summary, Map<String, Object> evidence,
                                  Map<String, Object> artifacts, double confidence,
                                  Map<String, Object> statePatch) {
        return new Observation(null, request.runId(), request.taskNodeId(), request.worker().name(),
                "succeeded", summary, evidence, artifacts, confidence, List.of(), request.worker().riskLevel(),
                false, null, "", false, List.of(),
                List.of(ConversationMessage.assistant(summary, providerName(), "observation",
                        Map.of("worker", request.worker().name()))),
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

