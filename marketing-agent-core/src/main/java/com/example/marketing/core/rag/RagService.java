package com.example.marketing.core.rag;

import java.util.List;

import com.example.marketing.core.context.MarketingAgentContext;

public interface RagService {
    List<RagDocument> retrieve(String query, MarketingAgentContext context);
}
