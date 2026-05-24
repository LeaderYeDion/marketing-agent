package com.example.marketing.core.middleware;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.harness.HarnessTraceEvent;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;

@Component
public class TraceMiddleware implements HarnessMiddleware {
    @Override
    public int order() {
        return 10;
    }

    @Override
    public void afterContextAssemble(HarnessInvocationContext invocation, HarnessContext context) {
        trace(invocation, "context_assembled", "ContextAssembler", "succeeded", Map.of(
                "workerCount", context.workers().size(),
                "visibleObjects", context.memory().visibleObjects().size(),
                "workspaceRefs", context.memory().workspaceRefs().keySet()
        ));
    }

    @Override
    public void beforePlan(HarnessInvocationContext invocation, HarnessContext context) {
        trace(invocation, "before_plan", "TaskPlanner", "running", Map.of(
                "workspaceRefs", context.memory().workspaceRefs().keySet()
        ));
    }

    @Override
    public void beforeModelCall(HarnessInvocationContext invocation, String purpose) {
        trace(invocation, "before_model_call", purpose, "running", Map.of());
    }

    @Override
    public void afterModelCall(HarnessInvocationContext invocation, String purpose, String status) {
        trace(invocation, "after_model_call", purpose, status, Map.of());
    }

    @Override
    public void afterPlan(HarnessInvocationContext invocation, TaskGraph graph) {
        trace(invocation, "after_plan", "TaskPlanner", graph == null ? "failed" : graph.status(), Map.of(
                "taskGraphId", graph == null ? "" : graph.id(),
                "nodeCount", graph == null ? 0 : graph.nodes().size()
        ));
    }

    @Override
    public WorkerCallDecision beforeWorkerCall(HarnessInvocationContext invocation, TaskGraph graph,
                                                       TaskNode node, WorkerDescriptor worker) {
        trace(invocation, "before_worker_call", worker.name(), "running", Map.of(
                "taskGraphId", graph.id(),
                "taskNodeId", node.id(),
                "risk", worker.riskLevel(),
                "requiresHumanApproval", worker.requiresHumanApproval()
        ));
        return WorkerCallDecision.proceed();
    }

    @Override
    public void afterWorkerCall(HarnessInvocationContext invocation, TaskGraph graph, TaskNode node,
                                    WorkerDescriptor worker, Observation observation) {
        trace(invocation, "after_worker_call", worker.name(), observation.status(), Map.of(
                "taskGraphId", graph.id(),
                "taskNodeId", node.id(),
                "observationId", observation.id()
        ));
    }

    @Override
    public Observation beforeObservationCommit(HarnessInvocationContext invocation, TaskGraph graph,
                                               Observation observation) {
        trace(invocation, "before_observation_commit", observation.workerName(), observation.status(), Map.of(
                "taskGraphId", graph == null ? "" : graph.id(),
                "taskNodeId", observation.taskNodeId(),
                "observationId", observation.id()
        ));
        return observation;
    }

    @Override
    public void afterObservationCommit(HarnessInvocationContext invocation, TaskGraph graph, Observation observation) {
        trace(invocation, "after_observation_commit", observation.workerName(), observation.status(), Map.of(
                "taskGraphId", graph == null ? "" : graph.id(),
                "taskNodeId", observation.taskNodeId(),
                "observationId", observation.id()
        ));
    }

    @Override
    public void onHumanApprovalRequired(HarnessInvocationContext invocation, TaskGraph graph, TaskNode node,
                                        WorkerDescriptor worker, Observation observation) {
        trace(invocation, "on_human_approval_required", worker.name(), observation.status(), Map.of(
                "taskGraphId", graph.id(),
                "taskNodeId", node.id(),
                "observationId", observation.id()
        ));
    }

    private void trace(HarnessInvocationContext invocation, String eventType, String source, String status,
                       Map<String, Object> data) {
        invocation.trace().add(HarnessTraceEvent.of(invocation.runId(), eventType, source, status, data));
    }
}

