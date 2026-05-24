package com.example.marketing.core.middleware;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;

public interface HarnessMiddleware {
    default int order() {
        return 100;
    }

    default void beforeContextAssemble(HarnessInvocationContext invocation) {
    }

    default void afterContextAssemble(HarnessInvocationContext invocation, HarnessContext context) {
    }

    default void beforePlan(HarnessInvocationContext invocation, HarnessContext context) {
    }

    default void afterPlan(HarnessInvocationContext invocation, TaskGraph graph) {
    }

    default WorkerCallDecision beforeWorkerCall(HarnessInvocationContext invocation,
                                                        TaskGraph graph,
                                                        TaskNode node,
                                                        WorkerDescriptor worker) {
        return WorkerCallDecision.proceed();
    }

    default void afterWorkerCall(HarnessInvocationContext invocation,
                                     TaskGraph graph,
                                     TaskNode node,
                                     WorkerDescriptor worker,
                                     Observation observation) {
    }

    default Observation beforeObservationCommit(HarnessInvocationContext invocation,
                                               TaskGraph graph,
                                               Observation observation) {
        return observation;
    }

    default void afterObservationCommit(HarnessInvocationContext invocation,
                                        TaskGraph graph,
                                        Observation observation) {
    }

    default void beforeModelCall(HarnessInvocationContext invocation, String purpose) {
    }

    default void afterModelCall(HarnessInvocationContext invocation, String purpose, String status) {
    }

    default void onContextOverflow(HarnessInvocationContext invocation, HarnessContext context) {
    }

    default void onHumanApprovalRequired(HarnessInvocationContext invocation,
                                         TaskGraph graph,
                                         TaskNode node,
                                         WorkerDescriptor worker,
                                         Observation observation) {
    }
}

