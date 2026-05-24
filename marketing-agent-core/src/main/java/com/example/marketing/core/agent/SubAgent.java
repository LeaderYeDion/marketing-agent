package com.example.marketing.core.agent;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;

public interface SubAgent {
    String name();

    default SubAgentCapabilities capabilities() {
        return SubAgentCapabilities.stateless(java.util.Set.of());
    }

    default SubAgentProfile profile() {
        return SubAgentProfile.general(name(), "General isolated sub-agent for read-only analysis.");
    }

    SubAgentResult run(SubAgentInvocation invocation, MarketingRequest request);
}
