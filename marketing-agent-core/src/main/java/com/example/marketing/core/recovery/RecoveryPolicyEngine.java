package com.example.marketing.core.recovery;

import org.springframework.stereotype.Service;

import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.observation.ObservationEvaluation;
import com.example.marketing.core.observation.ObservationEvaluator;
import com.example.marketing.core.task.TaskNode;

@Service
public class RecoveryPolicyEngine {
    private final ObservationEvaluator observationEvaluator;

    public RecoveryPolicyEngine(ObservationEvaluator observationEvaluator) {
        this.observationEvaluator = observationEvaluator;
    }

    public RecoveryDecision decide(WorkerDescriptor worker, TaskNode node, Observation observation) {
        ObservationEvaluation evaluation = observationEvaluator.evaluate(worker, node, observation);
        if (evaluation.needsFallback()) {
            return RecoveryDecision.fallback(worker.fallbackWorkerNames().getFirst(),
                    String.join(" ", evaluation.reasons()));
        }
        if (evaluation.needsReplan()) {
            return RecoveryDecision.replan(String.join(" ", evaluation.reasons()));
        }
        if (!"failed".equals(observation.status()) && !"waiting_for_user".equals(observation.status())) {
            return evaluation.usable()
                    ? RecoveryDecision.none("Observation is usable.")
                    : RecoveryDecision.replan(String.join(" ", evaluation.reasons()));
        }
        if ("waiting_for_user".equals(observation.status())) {
            return RecoveryDecision.askUser("Required inputs are missing: " + observation.missingInputs());
        }
        return RecoveryDecision.none("No configured recovery path is available.");
    }

    public RecoveryDecision decide(WorkerDescriptor worker, Observation observation) {
        return decide(worker, TaskNode.pending(observation.taskNodeId(), "", worker.name(), java.util.Map.of(),
                java.util.List.of()), observation);
    }
}

