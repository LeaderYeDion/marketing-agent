package com.example.marketing.core.capability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.agent.InquiryAgent;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.observation.Observation;

@Service
public class RuleInquiryCapabilityProvider implements CapabilityProvider {
    private final InquiryAgent inquiryAgent;

    public RuleInquiryCapabilityProvider(InquiryAgent inquiryAgent) {
        this.inquiryAgent = inquiryAgent;
    }

    @Override
    public String providerName() {
        return "rule_inquiry_provider";
    }

    @Override
    public boolean supports(CapabilityDescriptor descriptor) {
        return descriptor != null && "rule_inquiry".equals(descriptor.name())
                && providerName().equals(descriptor.provider());
    }

    @Override
    public Observation execute(CapabilityExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        String invocationId = "rule_" + UUID.randomUUID().toString().substring(0, 8);
        SubAgentInvocation invocation = new SubAgentInvocation(
                invocationId,
                executionRequest.conversationId(),
                executionRequest.taskNodeId(),
                executionRequest.capability().name(),
                List.of(executionRequest.capability().name()),
                executionRequest.userInput(),
                executionRequest.inputs(),
                executionRequest.compressedContext(),
                executionRequest.visibleObjects(),
                Map.of("provider_mode", providerName(),
                        "output_contract", executionRequest.capability().outputContract(),
                        "workspace_refs", executionRequest.workspaceRefs())
        );
        SubAgentResult result = inquiryAgent.run(invocation, marketingRequest);
        boolean succeeded = "succeeded".equals(result.status());
        return new Observation(
                null,
                executionRequest.runId(),
                executionRequest.taskNodeId(),
                executionRequest.capability().name(),
                result.status(),
                result.userVisibleSummary(),
                Map.of("provider_mode", providerName(),
                        "delegate_agent", inquiryAgent.name(),
                        "agent_context_summary", value(result.mainContextSummary()),
                        "workspace_refs", executionRequest.workspaceRefs()),
                Map.of("delegate_agent", inquiryAgent.name(), "invocation_id", result.invocationId(),
                        "workspace_refs", executionRequest.workspaceRefs()),
                succeeded ? 0.8 : 0.45,
                List.of(),
                executionRequest.capability().riskLevel(),
                false,
                null,
                result.failure().isEmpty() ? "" : String.valueOf(result.failure().getOrDefault("error_code", "")),
                false,
                result.visibleObjects(),
                result.messagesToCommit(),
                result.statePatch()
        );
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
