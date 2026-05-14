package com.example.marketing.core.agent;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;

public interface SubAgent {
    String name();

    SubAgentResult run(SubAgentInvocation invocation, MarketingRequest request);
}
