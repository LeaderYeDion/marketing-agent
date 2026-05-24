package com.example.marketing.core.capability;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.agent.SubAgent;
import com.example.marketing.core.agent.SubAgentProfile;
import com.example.marketing.core.agent.SubAgentProfileRegistry;
import com.example.marketing.core.agent.SubAgentRegistry;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.workspace.AgentWorkspace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class SubAgentDelegationCapabilityProvider implements CapabilityProvider {
    private final SubAgentRegistry subAgentRegistry;
    private final SubAgentProfileRegistry profileRegistry;
    private final AgentWorkspace workspace;
    private final ObjectMapper objectMapper;

    public SubAgentDelegationCapabilityProvider(SubAgentRegistry subAgentRegistry,
                                                SubAgentProfileRegistry profileRegistry,
                                                AgentWorkspace workspace,
                                                ObjectMapper objectMapper) {
        this.subAgentRegistry = subAgentRegistry;
        this.profileRegistry = profileRegistry;
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "subagent_delegation_provider";
    }

    @Override
    public boolean supports(CapabilityDescriptor descriptor) {
        return descriptor != null && "delegate_task".equals(descriptor.name())
                && providerName().equals(descriptor.provider());
    }

    @Override
    public Observation execute(CapabilityExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        String agentName = stringValue(executionRequest.inputs().get("agentName"));
        String task = stringValue(executionRequest.inputs().getOrDefault("task", executionRequest.userInput()));
        String expectedOutput = stringValue(executionRequest.inputs().get("expectedOutput"));
        SubAgentProfile profile = profileRegistry.find(agentName).orElse(null);
        SubAgent agent = subAgentRegistry.find(agentName).orElse(null);
        if (profile == null || agent == null) {
            return failed(executionRequest, agentName, task, "UNKNOWN_SUB_AGENT");
        }
        String invocationId = "delegate_" + UUID.randomUUID().toString().substring(0, 8);
        SubAgentInvocation invocation = new SubAgentInvocation(
                invocationId,
                executionRequest.conversationId(),
                executionRequest.taskNodeId(),
                executionRequest.capability().name(),
                profile.allowedSkills(),
                task,
                executionRequest.inputs(),
                executionRequest.compressedContext(),
                executionRequest.visibleObjects(),
                Map.of("expected_output", expectedOutput,
                        "subagent_profile", profile,
                        "workspace_refs", executionRequest.workspaceRefs())
        );
        SubAgentResult result = agent.run(invocation, marketingRequest);
        String path = writeInvocation(executionRequest, profile, invocation, result);
        boolean succeeded = "succeeded".equals(result.status());
        return new Observation(
                null,
                executionRequest.runId(),
                executionRequest.taskNodeId(),
                executionRequest.capability().name(),
                result.status(),
                result.userVisibleSummary(),
                Map.of("delegate_agent", agentName,
                        "subagent_profile", profile.name(),
                        "workspace_refs", executionRequest.workspaceRefs(),
                        "subagent_invocation_path", path),
                Map.of("delegate_agent", agentName,
                        "invocation_id", result.invocationId(),
                        "subagent_invocation_path", path),
                succeeded ? 0.7 : 0.35,
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

    private Observation failed(CapabilityExecutionRequest request, String agentName, String task, String errorType) {
        String summary = "无法委派子任务，未找到可用 sub-agent：" + agentName;
        return new Observation(null, request.runId(), request.taskNodeId(), request.capability().name(),
                "failed", summary, Map.of("delegate_agent", agentName, "task", task),
                Map.of(), 0.0, List.of("agentName"), request.capability().riskLevel(), false,
                null, errorType, false, List.of(), List.of(), Map.of());
    }

    private String writeInvocation(CapabilityExecutionRequest request, SubAgentProfile profile,
                                   SubAgentInvocation invocation, SubAgentResult result) {
        String path = "/subagents/" + safe(invocation.invocationId()) + "/result.json";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invocation_id", invocation.invocationId());
        payload.put("task_graph_id", request.taskGraphId());
        payload.put("task_node_id", request.taskNodeId());
        payload.put("agent_name", profile.name());
        payload.put("profile", profile);
        payload.put("task", invocation.task());
        payload.put("inputs", invocation.inputs());
        payload.put("result_status", result.status());
        payload.put("summary", result.userVisibleSummary());
        payload.put("main_context_summary", result.mainContextSummary());
        payload.put("failure", result.failure());
        workspace.write(request.conversationId(), path, toJson(payload), Map.of("type", "subagent_invocation", "agent_name", profile.name(),
                "task_node_id", request.taskNodeId(), "status", result.status()));
        return path;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
