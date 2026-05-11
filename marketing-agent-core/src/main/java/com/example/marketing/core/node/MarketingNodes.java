package com.example.marketing.core.node;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingStreamEvent;
import com.example.marketing.api.StreamEventTypes;
import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.context.MarketingEventPublisher;
import com.example.marketing.core.rag.RagDocument;
import com.example.marketing.core.rag.RagService;
import com.example.marketing.core.state.MarketingStateKeys;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

public class MarketingNodes {
    private final RagService ragService;

    public MarketingNodes(RagService ragService) {
        this.ragService = ragService;
    }

    public AsyncNodeAction contextNode() {
        return node_async(state -> {
            MarketingRequest request = required(state, MarketingStateKeys.REQUEST, MarketingRequest.class);
            MarketingAgentContext context = MarketingAgentContext.from(request);
            emit(state, MarketingNodeNames.CONTEXT, "已创建营销会话上下文", Map.of("channel", context.channel()));
            return Map.of(
                    MarketingStateKeys.CONTEXT, context,
                    MarketingStateKeys.TRACE, List.of("context")
            );
        });
    }

    public AsyncNodeAction intentNode() {
        return node_async(state -> {
            MarketingRequest request = required(state, MarketingStateKeys.REQUEST, MarketingRequest.class);
            String query = request.query() == null ? "" : request.query();
            String intent = detectIntent(query);
            emit(state, MarketingNodeNames.INTENT, "已识别营销意图：" + intent, Map.of("intent", intent));
            return Map.of(
                    MarketingStateKeys.INTENT, intent,
                    MarketingStateKeys.NEXT, query.isBlank() ? "fallback" : "continue",
                    MarketingStateKeys.TRACE, List.of("intent")
            );
        });
    }

    public AsyncNodeAction ragNode() {
        return node_async(state -> {
            MarketingRequest request = required(state, MarketingStateKeys.REQUEST, MarketingRequest.class);
            MarketingAgentContext context = required(state, MarketingStateKeys.CONTEXT, MarketingAgentContext.class);
            List<RagDocument> documents = ragService.retrieve(request.query(), context);
            emit(state, MarketingNodeNames.RAG, "已检索营销知识库", Map.of("documentCount", documents.size()));
            return Map.of(
                    MarketingStateKeys.RETRIEVED_DOCUMENTS, documents,
                    MarketingStateKeys.TRACE, List.of("rag")
            );
        });
    }

    public AsyncNodeAction planNode() {
        return node_async(state -> {
            MarketingAgentContext context = required(state, MarketingStateKeys.CONTEXT, MarketingAgentContext.class);
            String intent = state.value(MarketingStateKeys.INTENT).map(Object::toString).orElse("campaign_plan");
            List<String> plan = List.of(
                    "明确目标：" + goalText(context),
                    "定位人群：" + context.audience(),
                    "匹配渠道：" + context.channel(),
                    "生成方案：" + intent
            );
            emit(state, MarketingNodeNames.PLAN, "已拆解营销执行计划", Map.of("steps", plan.size()));
            return Map.of(
                    MarketingStateKeys.PLAN, plan,
                    MarketingStateKeys.TRACE, List.of("plan")
            );
        });
    }

    public AsyncNodeAction generateNode() {
        return node_async(state -> {
            MarketingRequest request = required(state, MarketingStateKeys.REQUEST, MarketingRequest.class);
            MarketingAgentContext context = required(state, MarketingStateKeys.CONTEXT, MarketingAgentContext.class);
            List<String> plan = listValue(state, MarketingStateKeys.PLAN, String.class);
            List<RagDocument> documents = listValue(state, MarketingStateKeys.RETRIEVED_DOCUMENTS, RagDocument.class);
            String answer = buildAnswer(request, context, plan, documents);
            List<String> suggestions = List.of("补充预算范围", "确认活动周期", "接入真实向量库与大模型");
            emit(state, MarketingNodeNames.GENERATE, "已生成营销助手回复", Map.of("suggestions", suggestions));
            return Map.of(
                    MarketingStateKeys.ANSWER, answer,
                    MarketingStateKeys.SUGGESTIONS, suggestions,
                    MarketingStateKeys.TRACE, List.of("generate")
            );
        });
    }

    public AsyncNodeAction fallbackNode() {
        return node_async(state -> {
            emit(state, MarketingNodeNames.FALLBACK, "用户问题为空，进入兜底回复", Map.of());
            return Map.of(
                    MarketingStateKeys.ANSWER, "请描述你的营销目标，例如：为新品设计一场面向会员的拉新活动。",
                    MarketingStateKeys.SUGGESTIONS, List.of("输入营销目标", "补充产品和人群", "指定渠道"),
                    MarketingStateKeys.TRACE, List.of("fallback")
            );
        });
    }

    private String detectIntent(String query) {
        if (query.contains("文案") || query.contains("话术")) {
            return "copywriting";
        }
        if (query.contains("复盘") || query.contains("指标")) {
            return "analysis";
        }
        if (query.contains("优惠") || query.contains("券")) {
            return "promotion";
        }
        return "campaign_plan";
    }

    private String buildAnswer(MarketingRequest request, MarketingAgentContext context, List<String> plan,
                               List<RagDocument> documents) {
        String references = documents.stream()
                .map(document -> "- " + document.title() + "：" + document.content())
                .reduce("", (left, right) -> left + "\n" + right);
        return """
                营销助手建议

                需求：%s
                产品：%s
                人群：%s
                渠道：%s

                执行计划：
                %s

                可参考知识：
                %s
                """.formatted(
                request.query(),
                context.product(),
                context.audience(),
                context.channel(),
                String.join("\n", plan.stream().map(step -> "- " + step).toList()),
                references.isBlank() ? "- 暂无匹配资料" : references
        );
    }

    private String goalText(MarketingAgentContext context) {
        return context.goals().isEmpty() ? "提升转化与 ROI" : String.join("、", context.goals());
    }

    private void emit(OverAllState state, String node, String message, Map<String, Object> data) {
        String conversationId = state.value(MarketingStateKeys.REQUEST)
                .filter(MarketingRequest.class::isInstance)
                .map(MarketingRequest.class::cast)
                .map(MarketingRequest::conversationId)
                .orElse("unknown");
        MarketingEventPublisher.publish(MarketingStreamEvent.of(
                conversationId, StreamEventTypes.NODE_END, node, message, data));
    }

    private <T> T required(OverAllState state, String key, Class<T> type) {
        return state.value(key)
                .filter(type::isInstance)
                .map(type::cast)
                .orElseThrow(() -> new IllegalStateException("Missing state key: " + key));
    }

    private <T> List<T> listValue(OverAllState state, String key, Class<T> elementType) {
        return state.value(key)
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .orElse(List.of())
                .stream()
                .filter(elementType::isInstance)
                .map(elementType::cast)
                .toList();
    }
}
