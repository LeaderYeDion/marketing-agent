package com.example.marketing.core.memory;

import java.util.List;

import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.agent.SubAgentProfile;
import com.example.marketing.core.context.MarketingAgentContext;

public record HarnessContext(
        MarketingAgentContext requestContext,
        HarnessMemory memory,
        List<CapabilityDescriptor> capabilities,
        List<SubAgentProfile> subAgentProfiles,
        String compressedContext
) {
    public HarnessContext {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        subAgentProfiles = subAgentProfiles == null ? List.of() : List.copyOf(subAgentProfiles);
        compressedContext = compressedContext == null ? "" : compressedContext;
    }
}
