package com.example.marketing.app;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.MarketingAgentService;
import com.example.marketing.core.llm.LlmClient;
import com.example.marketing.core.workspace.AgentWorkspace;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.dashscope.api-key=test-key")
class MarketingAgentApplicationTests {
    @Autowired
    private MarketingAgentService marketingAgentService;
    @Autowired
    private AgentWorkspace agentWorkspace;

    @Test
    void contextLoads() {
    }

    @Test
    void runMarketingGraph() {
        MarketingResponse response = marketingAgentService.run(new MarketingRequest(
                null,
                "demo-user",
                "活动报名规则有哪些？",
                "社群",
                "会员月卡",
                "一二线城市白领",
                List.of("拉新", "转化"),
                null
        ));

        assertThat(response.answer()).contains("基于知识库");
        assertThat(response.metadata()).containsKey("decision");
    }

    @Test
    void harnessBlocksDirectSideEffectWorkerBeforeApproval() {
        MarketingResponse response = marketingAgentService.run(new MarketingRequest(
                null,
                "demo-user",
                "force_enrollment_execute",
                "社群",
                "会员月卡",
                "一二线城市白领",
                List.of("报名"),
                Map.of("excel_file_path", "E:/tmp/not-needed-before-approval.csv", "activity_id", "A100")
        ));

        assertThat(response.answer()).contains("Approval is required");
        assertThat(String.valueOf(response.metadata().get("harness"))).contains("waiting_for_approval");
        assertThat(String.valueOf(response.metadata().get("pendingActions"))).contains("confirm_");
        assertThat(String.valueOf(response.metadata().get("harnessTrace"))).contains("hitl_boundary_enforced");
        assertThat(String.valueOf(response.metadata().get("harnessTrace")))
                .contains("before_model_call")
                .contains("after_model_call")
                .contains("before_worker_call")
                .contains("tool_permission_evaluated")
                .contains("on_human_approval_required")
                .contains("before_observation_commit")
                .contains("after_observation_commit");
        assertThat(String.valueOf(response.metadata().get("middleware")))
                .contains("ContextBudgetMiddleware")
                .contains("TraceMiddleware")
                .contains("ToolPermissionMiddleware")
                .contains("WorkspaceOffloadMiddleware");
        assertThat(String.valueOf(response.metadata().get("observations")))
                .contains("provider_not_invoked_before_approval");
    }

    @Test
    void workerCatalogExposesDecomposedEnrollmentWorkers() {
        MarketingResponse response = marketingAgentService.run(new MarketingRequest(
                null,
                "demo-user",
                "活动报名规则有哪些？",
                "社群",
                "会员月卡",
                "一二线城市白领",
                List.of("拉新", "转化"),
                null
        ));

        String workers = String.valueOf(response.metadata().get("workers"));
        assertThat(workers).contains("spreadsheet_summarize");
        assertThat(workers).contains("spreadsheet_query_product");
        assertThat(workers).contains("activity_rule_check");
        assertThat(workers).contains("enrollment_preview_create");
        assertThat(workers).contains("enrollment_execute");
        assertThat(workers).contains("notification_copywriting");
        assertThat(workerNames(response)).doesNotContain("activity_enroll");
        assertThat(workerProviders(response)).contains("rule_inquiry_provider");
        assertThat(workerProviders(response)).doesNotContain("inquiry_agent", "activity_enroll_agent");
    }

    @Test
    void legacyActivityEnrollSkillIsNotAvailableOnClasspath() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        assertThat(classLoader.getResource("skills/activity_enroll/manifest.yaml")).isNull();
        assertThat(classLoader.getResource("skills/activity_enroll/skill.md")).isNull();
    }

    @Test
    void enrollmentPreviewCreatesFullyBoundPendingAction(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("enroll.csv");
        Files.writeString(csv, "product_id,price\n123,99\n");
        MarketingResponse response = marketingAgentService.run(new MarketingRequest(
                null,
                "demo-user",
                "create_enrollment_preview",
                "社群",
                "",
                "",
                List.of("生成报名预览"),
                Map.of("excel_file_path", csv.toString(), "activity_id", "A100")
        ));

        String observations = String.valueOf(response.metadata().get("observations"));
        assertThat(response.answer()).contains("waiting for approval");
        assertThat(observations).contains("waiting_for_approval");
        assertThat(observations).contains("worker_name=enrollment_execute");
        assertThat(observations).contains("task_graph_id=");
        assertThat(observations).contains("task_node_id=node_1_approved_execution");
        assertThat(observations).contains("idempotency_key=activity_enroll:");
    }

    @Test
    void waitingForUserResumesOriginalTaskGraph(@TempDir Path tempDir) throws Exception {
        String conversationId = "resume-waiting-graph";
        MarketingResponse first = marketingAgentService.run(new MarketingRequest(
                conversationId,
                "demo-user",
                "need_excel_path",
                "社群",
                "",
                "",
                List.of("检查报名表"),
                null
        ));

        assertThat(first.answer()).contains("excel_file_path");
        assertThat(String.valueOf(first.metadata().get("harness"))).contains("waiting_for_user");
        assertThat(String.valueOf(first.metadata().get("state"))).contains("waiting_node");

        Path csv = tempDir.resolve("enroll.csv");
        Files.writeString(csv, "product_id,price\n123,99\n");
        MarketingResponse second = marketingAgentService.run(new MarketingRequest(
                conversationId,
                "demo-user",
                "补充文件路径",
                "社群",
                "",
                "",
                List.of("检查报名表"),
                Map.of("excel_file_path", csv.toString())
        ));

        assertThat(second.answer()).contains("Summarized spreadsheet");
        assertThat(String.valueOf(second.metadata().get("harnessTrace"))).contains("waiting_graph_resumed");
    }

    @Test
    void validatorRepairsInvalidPlannerDependencies() {
        MarketingResponse response = marketingAgentService.run(new MarketingRequest(
                null,
                "demo-user",
                "invalid_dependency_plan",
                "社群",
                "",
                "",
                List.of("规则检查"),
                null
        ));

        assertThat(String.valueOf(response.metadata().get("validation"))).contains("INVALID_DEPENDENCY");
        assertThat(String.valueOf(response.metadata().get("validation"))).contains("Removed invalid dependencies");
    }

    @Test
    @SuppressWarnings("unchecked")
    void observationsAndEvictedHistoryAreRecoverableThroughWorkspaceRefs() {
        String conversationId = "workspace-context-runtime";
        MarketingResponse response = null;
        for (int i = 0; i < 14; i++) {
            response = marketingAgentService.run(new MarketingRequest(
                    conversationId,
                    "demo-user",
                    "What are the activity enrollment rules? " + i,
                    "wechat",
                    "membership card",
                    "city white-collar users",
                    List.of("enrollment", "rules"),
                    null
            ));
        }

        Map<String, Object> refs = (Map<String, Object>) response.metadata().get("workspaceRefs");
        assertThat(String.valueOf(refs.get("conversation_history"))).startsWith("/conversation_history/");
        assertThat(String.valueOf(refs.get("latest_observation"))).contains("/observations/");
        assertThat(String.valueOf(response.metadata().get("workspace"))).contains("/conversation_history");
        assertThat(String.valueOf(response.metadata().get("workspace"))).contains("/observations");
        assertThat(String.valueOf(response.metadata().get("workspace"))).contains("/artifacts");
        assertThat(String.valueOf(response.metadata().get("workspace"))).contains("/evidence");

        String historyPath = String.valueOf(refs.get("conversation_history"));
        assertThat(agentWorkspace.read(conversationId, historyPath).content())
                .contains("Archived conversation history")
                .contains("What are the activity enrollment rules");
        assertThat(agentWorkspace.search(conversationId, "/observations", "rule_inquiry")).isNotEmpty();
    }

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        LlmClient stubLlmClient() {
            return (systemMessage, messages) -> {
                if (systemMessage.contains("TASK_GRAPH_PLANNER")) {
                    if (messages.stream().anyMatch(message -> message.content().contains("force_enrollment_execute"))) {
                        return """
                                {
                                  "rationale": "This intentionally attempts a direct side-effect execution so the harness boundary can be tested.",
                                  "answerStrategy": "The harness must pause for approval before provider execution.",
                                  "nodes": [
                                    {
                                      "id": "node_1",
                                      "goal": "Execute activity enrollment directly.",
                                      "workerName": "enrollment_execute",
                                      "dependsOn": [],
                                      "inputs": {
                                        "excel_file_path": "E:/tmp/not-needed-before-approval.csv",
                                        "activity_id": "A100"
                                      },
                                      "completionCriteria": "Enrollment is executed only after approval.",
                                      "priority": 100,
                                      "rationale": "Direct execution is high risk and should be blocked by harness."
                                    }
                                  ]
                                }
                                """;
                    }
                    if (messages.stream().anyMatch(message -> message.content().contains("need_excel_path"))) {
                        return """
                                {
                                  "rationale": "The user wants to inspect a spreadsheet but has not supplied the file path.",
                                  "answerStrategy": "Ask for the missing file path and resume the same node later.",
                                  "nodes": [
                                    {
                                      "id": "node_1",
                                      "goal": "Summarize the registration spreadsheet.",
                                      "workerName": "spreadsheet_summarize",
                                      "dependsOn": [],
                                      "inputs": {},
                                      "completionCriteria": "Spreadsheet summary is produced.",
                                      "priority": 100,
                                      "rationale": "spreadsheet_summarize needs excel_file_path."
                                    }
                                  ]
                                }
                                """;
                    }
                    if (messages.stream().anyMatch(message -> message.content().contains("create_enrollment_preview"))) {
                        return """
                                {
                                  "rationale": "The user wants a preview card before executing activity enrollment.",
                                  "answerStrategy": "Create a preview and pause at the HITL boundary.",
                                  "nodes": [
                                    {
                                      "id": "node_1",
                                      "goal": "Create an activity enrollment preview for approval.",
                                      "workerName": "enrollment_preview_create",
                                      "dependsOn": [],
                                      "inputs": {},
                                      "completionCriteria": "A pending action proposal is created.",
                                      "priority": 100,
                                      "rationale": "Preview is the safe proposal boundary before enrollment_execute."
                                    }
                                  ]
                                }
                                """;
                    }
                    if (messages.stream().anyMatch(message -> message.content().contains("invalid_dependency_plan"))) {
                        return """
                                {
                                  "rationale": "This intentionally contains an invalid dependency so validation repair can be tested.",
                                  "answerStrategy": "Return the rule answer after repair.",
                                  "nodes": [
                                    {
                                      "id": "node_1",
                                      "goal": "Answer a rule question.",
                                      "workerName": "rule_inquiry",
                                      "dependsOn": ["missing_node"],
                                      "inputs": {"question": "活动规则是什么？"},
                                      "completionCriteria": "A grounded answer is produced.",
                                      "priority": 100,
                                      "rationale": "The dependency is invalid and should be removed."
                                    }
                                  ]
                                }
                                """;
                    }
                    return """
                            {
                              "rationale": "The user is asking for marketing activity enrollment rules.",
                              "answerStrategy": "Return the grounded rule inquiry result.",
                              "nodes": [
                                {
                                  "id": "node_1",
                                  "goal": "Answer the marketing rule question with grounded context.",
                                  "workerName": "rule_inquiry",
                                  "dependsOn": [],
                                  "inputs": {
                                    "question": "活动报名规则有哪些？"
                                  },
                                  "completionCriteria": "A grounded answer is produced.",
                                  "priority": 100,
                                  "rationale": "rule_inquiry is the catalog worker for marketing rule questions."
                                }
                              ]
                            }
                            """;
                }
                if (systemMessage.contains("agent")) {
                    return "基于知识库，活动报名通常需要确认活动 ID、报名对象、优惠规则和生效条件。";
                }
                return "已完成。";
            };
        }

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(
                            "基于知识库，活动报名通常需要确认活动 ID、报名对象、优惠规则和生效条件。"))));
                }
            };
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> workerNames(MarketingResponse response) {
        Object workers = response.metadata().get("workers");
        assertThat(workers).isInstanceOf(List.class);
        return ((List<Map<String, Object>>) workers).stream()
                .map(worker -> String.valueOf(worker.get("name")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> workerProviders(MarketingResponse response) {
        Object workers = response.metadata().get("workers");
        assertThat(workers).isInstanceOf(List.class);
        return ((List<Map<String, Object>>) workers).stream()
                .map(worker -> String.valueOf(worker.get("provider")))
                .toList();
    }
}
