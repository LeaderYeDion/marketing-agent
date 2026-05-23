package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.ToolResult;
import com.example.marketing.core.rag.RagDocument;
import com.example.marketing.core.skill.SkillRegistry;
import com.example.marketing.core.tool.KnowledgeTools;
import com.example.marketing.core.tool.WorkspaceTools;

@Service
public class InquiryAgent implements SubAgent {
    private final KnowledgeTools knowledgeTools;
    private final WorkspaceTools workspaceTools;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final SkillRegistry skillRegistry;

    public InquiryAgent(KnowledgeTools knowledgeTools, WorkspaceTools workspaceTools,
                        ObjectProvider<ChatModel> chatModelProvider,
                        SkillRegistry skillRegistry) {
        this.knowledgeTools = knowledgeTools;
        this.workspaceTools = workspaceTools;
        this.chatModelProvider = chatModelProvider;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String name() {
        return "inquiry_agent";
    }

    @Override
    public SubAgentCapabilities capabilities() {
        return SubAgentCapabilities.stateless(java.util.Set.of("question"));
    }

    @Override
    public SubAgentResult run(SubAgentInvocation invocation, MarketingRequest request) {
        return run(invocation, MarketingAgentContext.from(request));
    }

    public SubAgentResult run(SubAgentInvocation invocation, MarketingAgentContext context) {
        SubAgentEventSink sink = new SubAgentEventSink(invocation.conversationId(), invocation.invocationId(),
                name());
        String question = stringValue(invocation.inputs().getOrDefault("question", invocation.task()));
        List<ConversationMessage> commits = new ArrayList<>();
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            String message = "当前没有可用的大模型配置，无法完成开放式规则咨询。请稍后重试。";
            commits.add(ConversationMessage.assistant(message, name(), "failure", Map.of()));
            return SubAgentResult.failed(invocation.invocationId(), message, message,
                    Map.of("error_code", "CHAT_MODEL_NOT_AVAILABLE"), commits);
        }

        InquiryTools tools = new InquiryTools(invocation, context, sink);
        try {
            AssistantMessage answer = AgenticSubAgentSupport.runReactAgent(
                    name(),
                    inquiryInstruction(),
                    chatModel,
                    skillRegistry,
                    tools.callbacks(),
                    AgenticSubAgentSupport.toMessages(inquirySystemMessage(), invocation, question, List.of()),
                    invocation.conversationId() + ":" + invocation.invocationId());
            String text = blankToDefault(answer.getText(), "我没有得到足够的信息完成判断，请补充活动、优惠或报名规则相关背景。");
            sink.token(text);
            commits.add(ConversationMessage.assistant(text, name(), "final_answer",
                    Map.of("invocationId", invocation.invocationId())));
            return new SubAgentResult(invocation.invocationId(), "succeeded", text,
                    "规则咨询智能体已基于可用技能、工具和检索证据回答用户问题：" + question,
                    Map.of("current_task", Map.of("type", "rule_inquiry", "status", "succeeded")),
                    List.of(), commits, Map.of());
        }
        catch (RuntimeException ex) {
            String message = "我在规则咨询推理过程中遇到错误：" + ex.getMessage();
            commits.add(ConversationMessage.assistant(message, name(), "failure",
                    Map.of("error", ex.getClass().getSimpleName())));
            return SubAgentResult.failed(invocation.invocationId(), message,
                    "规则咨询智能体执行失败：" + ex.getMessage(),
                    Map.of("error_code", "AGENT_RUNTIME_ERROR"), commits);
        }
    }

    private String inquiryInstruction() {
        return """
                You are a marketing rule inquiry agent.
                You are not a fixed RAG chain. Reason about the user's question, decide whether evidence is needed,
                choose tools, inspect observations, and then reason again.
                Use read_skill when a skill may help.
                Use read_workspace or search_workspace when prior observations, artifacts, or archived conversation
                refs may contain evidence. Workspace tools are read-only and scoped to the current conversation.
                Use search tools when the answer depends on rules, policies, promotion limits, enrollment details,
                or prior knowledge. Evaluate whether retrieved evidence is relevant before answering.
                If evidence is weak, rewrite the query, search a different knowledge base, or ask for clarification.
                Do not fabricate policy details. Answer in Chinese.
                """;
    }

    private String inquirySystemMessage() {
        return """
                你是规则咨询领域智能体。用户可能输入任何内容，包括闲聊、无关问题、模糊问题、具体规则问题、
                或对已有卡片/活动/优惠的追问。你的职责是理解意图，决定是否需要工具，基于观察结果回答。

                可接受行为：
                - 问题足够简单且上下文已充分时，直接回答。
                - 需要证据时，调用检索工具，必要时多次检索。
                - 召回内容价值低时，说明不足并追问，或改写 query 再查。
                - 用户问题超出营销规则/活动/报名/优惠范围时，礼貌说明边界，并尽量给出可转向的问题。
                """;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private final class InquiryTools {
        private final SubAgentInvocation invocation;
        private final MarketingAgentContext context;
        private final SubAgentEventSink sink;

        private InquiryTools(SubAgentInvocation invocation, MarketingAgentContext context, SubAgentEventSink sink) {
            this.invocation = invocation;
            this.context = context;
            this.sink = sink;
        }

        private List<ToolCallback> callbacks() {
            return List.of(
                    FunctionToolCallback.builder("search_marketing_knowledge", this::searchMarketingKnowledge)
                            .description("Search the default marketing knowledge base for rules, policies, cases, and metrics.")
                            .inputType(SearchRequest.class)
                            .build(),
                    FunctionToolCallback.builder("search_knowledge_base", this::searchKnowledgeBase)
                            .description("Search a specific logical knowledge base. Supported values include rule, promotion, enrollment, case, risk, metric, or default.")
                            .inputType(SearchBaseRequest.class)
                            .build(),
                    FunctionToolCallback.builder("read_workspace", this::readWorkspace)
                            .description("Read a read-only workspace document for this conversation, such as an observation, artifact, or archived conversation history path from workspace_refs.")
                            .inputType(WorkspaceReadRequest.class)
                            .build(),
                    FunctionToolCallback.builder("search_workspace", this::searchWorkspace)
                            .description("Search read-only workspace documents for this conversation under a path prefix. Use this to recover evidence from previous observations or archived history.")
                            .inputType(WorkspaceSearchRequest.class)
                            .build());
        }

        private String searchMarketingKnowledge(SearchRequest request) {
            String query = blankToDefault(request.query(), "");
            sink.toolStart("search_marketing_knowledge", Map.of("query", query));
            ToolResult result = knowledgeTools.searchRelatedKnowledge(query, context);
            sink.toolEnd("search_marketing_knowledge", Map.of("ok", result.ok(),
                    "documentCount", result.data().getOrDefault("document_count", 0)));
            return result.data().toString();
        }

        private String searchKnowledgeBase(SearchBaseRequest request) {
            String base = blankToDefault(request.knowledgeBase(), "default");
            String query = (blankToDefault(request.query(), "") + " " + base).trim();
            sink.toolStart("search_knowledge_base", Map.of("query", query, "knowledgeBase", base));
            ToolResult result = knowledgeTools.searchRelatedKnowledge(query, context);
            sink.toolEnd("search_knowledge_base", Map.of("ok", result.ok(),
                    "documentCount", result.data().getOrDefault("document_count", 0)));
            return "knowledgeBase=" + base + ", result=" + result.data();
        }

        private String readWorkspace(WorkspaceReadRequest request) {
            String path = blankToDefault(request.path(), "");
            sink.toolStart("read_workspace", Map.of("path", path));
            Map<String, Object> result = workspaceTools.readableView(invocation.conversationId(), path);
            sink.toolEnd("read_workspace", Map.of("path", result.get("path"),
                    "missing", String.valueOf(result.get("metadata")).contains("missing=true")));
            return result.toString();
        }

        private String searchWorkspace(WorkspaceSearchRequest request) {
            String path = blankToDefault(request.path(), "/");
            String query = blankToDefault(request.query(), "");
            sink.toolStart("search_workspace", Map.of("path", path, "query", query));
            List<Map<String, Object>> result = workspaceTools.searchableView(invocation.conversationId(), path, query);
            sink.toolEnd("search_workspace", Map.of("path", path, "query", query, "hitCount", result.size()));
            return result.toString();
        }

    }

    private record SearchRequest(String query, String reason) {
    }

    private record SearchBaseRequest(String query, String knowledgeBase, String reason) {
    }

    private record WorkspaceReadRequest(String path, String reason) {
    }

    private record WorkspaceSearchRequest(String path, String query, String reason) {
    }

}
