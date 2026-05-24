package com.example.marketing.core.worker;

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
public class RuleInquiryWorkerProvider implements WorkerProvider {
    private final InquiryAgent inquiryAgent;

    public RuleInquiryWorkerProvider(InquiryAgent inquiryAgent) {
        this.inquiryAgent = inquiryAgent;
    }

    @Override
    public String providerName() {
        return "rule_inquiry_provider";
    }

    @Override
    public boolean supports(WorkerDescriptor descriptor) {
        return descriptor != null && "rule_inquiry".equals(descriptor.name())
                && providerName().equals(descriptor.provider());
    }

    @Override
    public Observation execute(WorkerExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        String invocationId = "rule_" + UUID.randomUUID().toString().substring(0, 8);
        SubAgentInvocation invocation = new SubAgentInvocation(
                invocationId,
                executionRequest.conversationId(),
                executionRequest.taskNodeId(),
                executionRequest.worker().name(),
                List.of(executionRequest.worker().name()),
                executionRequest.userInput(),
                executionRequest.inputs(),
                executionRequest.compressedContext(),
                executionRequest.visibleObjects(),
                Map.of("provider_mode", providerName(),
                        "output_contract", executionRequest.worker().outputContract(),
                        "workspace_refs", executionRequest.workspaceRefs())
        );
        SubAgentResult result = inquiryAgent.run(invocation, marketingRequest);
        boolean succeeded = "succeeded".equals(result.status());
        return new Observation(
                null,
                executionRequest.runId(),
                executionRequest.taskNodeId(),
                executionRequest.worker().name(),
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
                executionRequest.worker().riskLevel(),
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

