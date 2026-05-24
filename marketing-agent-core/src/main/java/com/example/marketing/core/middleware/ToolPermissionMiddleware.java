package com.example.marketing.core.middleware;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.policy.RiskAssessment;
import com.example.marketing.core.policy.RiskPolicyEngine;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;

@Component
public class ToolPermissionMiddleware implements HarnessMiddleware {
    private final RiskPolicyEngine riskPolicyEngine;

    public ToolPermissionMiddleware(RiskPolicyEngine riskPolicyEngine) {
        this.riskPolicyEngine = riskPolicyEngine;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public WorkerCallDecision beforeWorkerCall(HarnessInvocationContext invocation, TaskGraph graph,
                                                       TaskNode node, WorkerDescriptor worker) {
        RiskAssessment risk = riskPolicyEngine.assess(worker, node, node.inputs());
        invocation.put("risk:" + node.id(), risk);
        invocation.trace().add(com.example.marketing.core.harness.HarnessTraceEvent.of(invocation.runId(),
                "tool_permission_evaluated", worker.name(), risk.requiresApproval() ? "approval_required" :
                "allowed", Map.of(
                        "taskGraphId", graph.id(),
                        "taskNodeId", node.id(),
                        "riskLevel", risk.riskLevel(),
                        "requiresApproval", risk.requiresApproval(),
                        "readOnly", risk.readOnly(),
                        "reason", risk.reason()
                )));
        return WorkerCallDecision.proceed(risk);
    }
}

