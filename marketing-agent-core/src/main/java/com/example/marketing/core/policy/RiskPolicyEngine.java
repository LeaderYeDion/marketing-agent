package com.example.marketing.core.policy;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.task.TaskNode;

@Service
public class RiskPolicyEngine {
    public RiskAssessment assess(WorkerDescriptor worker, TaskNode taskNode, Map<String, Object> inputs) {
        boolean highRisk = "high".equalsIgnoreCase(worker.riskLevel()) || worker.sideEffects();
        boolean approvedByPendingAction = Boolean.TRUE.equals(inputs == null ? null : inputs.get("pending_action_approved"));
        boolean requiresApproval = (worker.requiresHumanApproval() || highRisk) && !approvedByPendingAction;
        boolean readOnly = !worker.sideEffects() && worker.permissions().stream()
                .noneMatch(permission -> permission.contains("operation") || permission.contains("write"));
        String reason = requiresApproval
                ? "Worker can affect business state and must stay behind a human approval boundary."
                : "Worker is read-only or execution was authorized by a pending action state-machine transition.";
        return new RiskAssessment(worker.name(), worker.riskLevel(), requiresApproval, readOnly, reason);
    }
}

