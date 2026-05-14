package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.llm.LlmClient;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.ToolResult;
import com.example.marketing.core.tool.KnowledgeTools;

@Service
public class InquiryAgent implements SubAgent {
    private final KnowledgeTools knowledgeTools;
    private final LlmClient llmClient;

    public InquiryAgent(KnowledgeTools knowledgeTools, LlmClient llmClient) {
        this.knowledgeTools = knowledgeTools;
        this.llmClient = llmClient;
    }

    @Override
    public String name() {
        return "inquiry_agent";
    }

    @Override
    public SubAgentResult run(SubAgentInvocation invocation, MarketingRequest request) {
        return run(invocation, MarketingAgentContext.from(request));
    }

    public SubAgentResult run(SubAgentInvocation invocation, MarketingAgentContext context) {
        SubAgentEventSink sink = new SubAgentEventSink(invocation.conversationId(), invocation.invocationId(),
                "inquiry_agent");
        String question = String.valueOf(invocation.inputs().getOrDefault("question", invocation.task()));
        sink.toolStart("search_related_knowledge", Map.of("question", question));
        ToolResult rag = knowledgeTools.searchRelatedKnowledge(question, context);
        sink.toolEnd("search_related_knowledge", Map.of("ok", rag.ok()));
        String answer = generateAnswer(invocation, question, rag);
        sink.token(answer);
        List<ConversationMessage> commits = new ArrayList<>();
        commits.add(ConversationMessage.assistant(answer, "inquiry_agent", "final_answer",
                Map.of("invocationId", invocation.invocationId())));
        return new SubAgentResult(invocation.invocationId(), "succeeded", answer,
                "问答子任务已回答用户问题：" + question, Map.of("current_task", Map.of("type", "rule_inquiry",
                "status", "succeeded")), List.of(), commits, Map.of());
    }

    private String generateAnswer(SubAgentInvocation invocation, String question, ToolResult rag) {
        String system = """
                你是营销规则问答子 agent。你可以基于主 agent 提炼的上下文、用户可见对象摘要和知识库检索结果回答。
                如果依据不足，请自然说明缺少什么。回答时说明依据来自哪里。
                """;
        String prompt = """
                用户问题：%s
                主 agent 提炼的上下文：%s
                用户可见对象：%s
                RAG 结果：%s
                """.formatted(question, invocation.compressedContext(), invocation.visibleObjects(), rag.data());
        try {
            return llmClient.generate(system, List.of(ConversationMessage.user(prompt, Map.of())));
        }
        catch (RuntimeException ex) {
            return "我现在无法调用大模型完成完整分析，但可以基于当前上下文判断：这个问题与营销活动或优惠报名规则相关。"
                    + "请补充活动 ID、优惠 ID 或你看到的卡片内容，我可以继续帮你定位。";
        }
    }
}
