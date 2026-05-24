package com.example.marketing.core.middleware;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;

@Service
public class HarnessMiddlewareChain {
    private final List<HarnessMiddleware> middlewares;

    public HarnessMiddlewareChain(List<HarnessMiddleware> middlewares) {
        this.middlewares = middlewares == null ? List.of() : middlewares.stream()
                .sorted(Comparator.comparingInt(HarnessMiddleware::order))
                .toList();
    }

    public List<String> names() {
        return middlewares.stream().map(middleware -> middleware.getClass().getSimpleName()).toList();
    }

    public void beforeContextAssemble(HarnessInvocationContext invocation) {
        middlewares.forEach(middleware -> middleware.beforeContextAssemble(invocation));
    }

    public void afterContextAssemble(HarnessInvocationContext invocation, HarnessContext context) {
        middlewares.forEach(middleware -> middleware.afterContextAssemble(invocation, context));
    }

    public void beforePlan(HarnessInvocationContext invocation, HarnessContext context) {
        middlewares.forEach(middleware -> middleware.beforePlan(invocation, context));
    }

    public void afterPlan(HarnessInvocationContext invocation, TaskGraph graph) {
        middlewares.forEach(middleware -> middleware.afterPlan(invocation, graph));
    }

    public void beforeModelCall(HarnessInvocationContext invocation, String purpose) {
        middlewares.forEach(middleware -> middleware.beforeModelCall(invocation, purpose));
    }

    public void afterModelCall(HarnessInvocationContext invocation, String purpose, String status) {
        middlewares.forEach(middleware -> middleware.afterModelCall(invocation, purpose, status));
    }

    public WorkerCallDecision beforeWorkerCall(HarnessInvocationContext invocation, TaskGraph graph,
                                                       TaskNode node, WorkerDescriptor worker) {
        WorkerCallDecision decision = WorkerCallDecision.proceed();
        for (HarnessMiddleware middleware : middlewares) {
            decision = decision.merge(middleware.beforeWorkerCall(invocation, graph, node, worker));
            if (!decision.allowed()) {
                return decision;
            }
        }
        return decision;
    }

    public void afterWorkerCall(HarnessInvocationContext invocation, TaskGraph graph, TaskNode node,
                                    WorkerDescriptor worker, Observation observation) {
        middlewares.forEach(middleware -> middleware.afterWorkerCall(invocation, graph, node, worker,
                observation));
    }

    public Observation beforeObservationCommit(HarnessInvocationContext invocation, TaskGraph graph,
                                               Observation observation) {
        Observation current = observation;
        for (HarnessMiddleware middleware : middlewares) {
            current = middleware.beforeObservationCommit(invocation, graph, current);
        }
        return current;
    }

    public void afterObservationCommit(HarnessInvocationContext invocation, TaskGraph graph, Observation observation) {
        middlewares.forEach(middleware -> middleware.afterObservationCommit(invocation, graph, observation));
    }

    public void onHumanApprovalRequired(HarnessInvocationContext invocation, TaskGraph graph, TaskNode node,
                                        WorkerDescriptor worker, Observation observation) {
        middlewares.forEach(middleware -> middleware.onHumanApprovalRequired(invocation, graph, node, worker,
                observation));
    }
}

