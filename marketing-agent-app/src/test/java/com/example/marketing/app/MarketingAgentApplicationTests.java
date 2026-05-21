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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.dashscope.api-key=test-key")
class MarketingAgentApplicationTests {
    @Autowired
    private MarketingAgentService marketingAgentService;

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
    void harnessBlocksDirectSideEffectCapabilityBeforeApproval() {
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

        assertThat(response.answer()).contains("需要你确认后才能执行");
        assertThat(String.valueOf(response.metadata().get("harness"))).contains("waiting_for_approval");
        assertThat(String.valueOf(response.metadata().get("pendingActions"))).contains("confirm_");
        assertThat(String.valueOf(response.metadata().get("harnessTrace"))).contains("hitl_boundary_enforced");
        assertThat(String.valueOf(response.metadata().get("observations")))
                .contains("provider_not_invoked_before_approval");
    }

    @Test
    void capabilityCatalogExposesDecomposedEnrollmentCapabilities() {
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

        String capabilities = String.valueOf(response.metadata().get("capabilities"));
        assertThat(capabilities).contains("spreadsheet_summarize");
        assertThat(capabilities).contains("spreadsheet_query_product");
        assertThat(capabilities).contains("activity_rule_check");
        assertThat(capabilities).contains("enrollment_preview_create");
        assertThat(capabilities).contains("enrollment_execute");
        assertThat(capabilities).contains("notification_copywriting");
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

        assertThat(second.answer()).contains("已读取报名表格");
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
                                      "capabilityName": "enrollment_execute",
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
                                      "capabilityName": "spreadsheet_summarize",
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
                    if (messages.stream().anyMatch(message -> message.content().contains("invalid_dependency_plan"))) {
                        return """
                                {
                                  "rationale": "This intentionally contains an invalid dependency so validation repair can be tested.",
                                  "answerStrategy": "Return the rule answer after repair.",
                                  "nodes": [
                                    {
                                      "id": "node_1",
                                      "goal": "Answer a rule question.",
                                      "capabilityName": "rule_inquiry",
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
                                  "capabilityName": "rule_inquiry",
                                  "dependsOn": [],
                                  "inputs": {
                                    "question": "活动报名规则有哪些？"
                                  },
                                  "completionCriteria": "A grounded answer is produced.",
                                  "priority": 100,
                                  "rationale": "rule_inquiry is the catalog capability for marketing rule questions."
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
}
