package com.example.marketing.core.capability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.agent.SubAgent;
import com.example.marketing.core.agent.SubAgentRegistry;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.observation.ActionProposal;
import com.example.marketing.core.observation.Observation;

@Service
public class SubAgentCapabilityProvider implements CapabilityProvider {
    private final SubAgentRegistry subAgentRegistry;

    public SubAgentCapabilityProvider(SubAgentRegistry subAgentRegistry) {
        this.subAgentRegistry = subAgentRegistry;
    }

    @Override
    public String providerName() {
        return "sub_agent";
    }

    @Override
    public boolean supports(CapabilityDescriptor descriptor) {
        return descriptor != null && subAgentRegistry.find(descriptor.provider()).isPresent();
    }

    @Override
    public Observation execute(CapabilityExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        CapabilityDescriptor capability = executionRequest.capability();
        SubAgent subAgent = subAgentRegistry.find(capability.provider())
                .orElseThrow(() -> new IllegalStateException("No sub-agent provider for " + capability.name()));
        String invocationId = "cap_" + UUID.randomUUID().toString().substring(0, 8);
        SubAgentInvocation invocation = new SubAgentInvocation(
                invocationId,
                executionRequest.conversationId(),
                executionRequest.taskNodeId(),
                capability.name(),
                List.of(capability.name()),
                executionRequest.userInput(),
                executionRequest.inputs(),
                executionRequest.compressedContext(),
                executionRequest.visibleObjects(),
                Map.of("output_contract", capability.outputContract(), "capability", capability.name())
        );
        SubAgentResult result = subAgent.run(invocation, marketingRequest);
        ActionProposal proposal = extractActionProposal(capability, result.visibleObjects());
        boolean requiresApproval = proposal != null || "hitl_required".equals(result.status());
        String status = "hitl_required".equals(result.status()) ? "waiting_for_approval" : result.status();
        return new Observation(
                null,
                executionRequest.runId(),
                executionRequest.taskNodeId(),
                capability.name(),
                status,
                result.userVisibleSummary(),
                Map.of("main_context_summary", result.mainContextSummary() == null ? "" : result.mainContextSummary()),
                Map.of("sub_agent", subAgent.name(), "invocation_id", result.invocationId()),
                "succeeded".equals(result.status()) ? 0.8 : 0.5,
                List.of(),
                capability.riskLevel(),
                requiresApproval,
                proposal,
                result.failure().isEmpty() ? "" : String.valueOf(result.failure().getOrDefault("error_code", "")),
                false,
                result.visibleObjects(),
                result.messagesToCommit(),
                result.statePatch()
        );
    }

    private ActionProposal extractActionProposal(CapabilityDescriptor capability, List<VisibleObject> visibleObjects) {
        return visibleObjects.stream()
                .filter(object -> "hitl_confirmation".equals(object.type()) && "pending".equals(object.status()))
                .findFirst()
                .map(object -> new ActionProposal(object.id(), object.type(), capability.name(), object.summary(),
                        object.data(), capability.riskLevel()))
                .orElse(null);
    }
}
