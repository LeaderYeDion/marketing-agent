package com.example.marketing.app;

import java.util.List;

import org.junit.jupiter.api.Test;
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

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        LlmClient stubLlmClient() {
            return (systemMessage, messages) -> {
                if (systemMessage.contains("TASK_GRAPH_PLANNER")) {
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
