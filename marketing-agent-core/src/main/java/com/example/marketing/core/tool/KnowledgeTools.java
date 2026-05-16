package com.example.marketing.core.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.model.ToolResult;
import com.example.marketing.core.rag.RagDocument;
import com.example.marketing.core.rag.RagService;

@Service
public class KnowledgeTools {
    private final RagService ragService;

    public KnowledgeTools(RagService ragService) {
        this.ragService = ragService;
    }

    public ToolResult searchRelatedKnowledge(String query, MarketingAgentContext context) {
        List<RagDocument> documents = ragService.retrieve(query, context);
        return ToolResult.ok("search_related_knowledge", Map.of(
                "documents", documents,
                "citations", documents.stream()
                        .map(document -> document.metadata().get("citation"))
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                "document_count", documents.size()
        ));
    }

    public ToolResult webSearch(String query) {
        return ToolResult.failed("web_search", "WEB_SEARCH_NOT_CONFIGURED",
                "当前环境还没有配置网络搜索工具，我会优先基于对话上下文和本地知识库回答。",
                "No web search provider configured", false);
    }
}
