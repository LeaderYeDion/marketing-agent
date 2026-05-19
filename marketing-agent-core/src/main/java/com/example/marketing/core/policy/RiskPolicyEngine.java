package com.example.marketing.core.policy;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.task.TaskNode;

@Service
public class RiskPolicyEngine {
    public RiskAssessment assess(CapabilityDescriptor capability, TaskNode taskNode, Map<String, Object> inputs) {
        boolean highRisk = "high".equalsIgnoreCase(capability.riskLevel()) || capability.sideEffects();
        boolean confirmed = Boolean.TRUE.equals(inputs == null ? null : inputs.get("confirmed"));
        boolean requiresApproval = capability.requiresHumanApproval() || (highRisk && !confirmed);
        boolean readOnly = !capability.sideEffects() && capability.permissions().stream()
                .noneMatch(permission -> permission.contains("operation") || permission.contains("write"));
        String reason = requiresApproval
                ? "Capability can affect business state and must stay behind a human approval boundary."
                : "Capability is read-only or already confirmed by a pending action transition.";
        return new RiskAssessment(capability.name(), capability.riskLevel(), requiresApproval, readOnly, reason);
    }
}
