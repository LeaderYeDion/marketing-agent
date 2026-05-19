package com.example.marketing.core.memory;

import java.util.List;

import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.context.MarketingAgentContext;

public record HarnessContext(
        MarketingAgentContext requestContext,
        HarnessMemory memory,
        List<CapabilityDescriptor> capabilities,
        String compressedContext
) {
    public HarnessContext {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        compressedContext = compressedContext == null ? "" : compressedContext;
    }
}
