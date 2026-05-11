package com.example.marketing.app;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.MarketingAgentService;
import com.example.marketing.core.llm.LlmClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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
                if (systemMessage.contains("主控 agent")) {
                    return """
                            {
                              "action": "delegate",
                              "skill_name": "rule_inquiry",
                              "delegate_to": "inquiry_agent",
                              "reply": "",
                              "question": "活动报名规则有哪些？",
                              "compressed_context": "用户咨询活动报名规则。"
                            }
                            """;
                }
                if (systemMessage.contains("营销规则问答子 agent")) {
                    return "基于知识库，活动报名通常需要确认活动 ID、报名对象、优惠规则和生效条件。";
                }
                return "已完成。";
            };
        }
    }
}
