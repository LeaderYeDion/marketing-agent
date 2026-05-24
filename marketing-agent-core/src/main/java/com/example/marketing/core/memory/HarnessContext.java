package com.example.marketing.core.memory;

import java.util.List;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.agent.SubAgentProfile;
import com.example.marketing.core.context.MarketingAgentContext;

public record HarnessContext(
        MarketingAgentContext requestContext,
        HarnessMemory memory,
        List<WorkerDescriptor> workers,
        List<SubAgentProfile> subAgentProfiles,
        String compressedContext
) {
    public HarnessContext {
        workers = workers == null ? List.of() : List.copyOf(workers);
        subAgentProfiles = subAgentProfiles == null ? List.of() : List.copyOf(subAgentProfiles);
        compressedContext = compressedContext == null ? "" : compressedContext;
    }
}

