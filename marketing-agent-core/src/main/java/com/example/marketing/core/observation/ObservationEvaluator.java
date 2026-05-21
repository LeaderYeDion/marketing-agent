package com.example.marketing.core.observation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.task.TaskNode;

@Service
public class ObservationEvaluator {
    public ObservationEvaluation evaluate(CapabilityDescriptor capability, TaskNode node, Observation observation) {
        List<String> reasons = new ArrayList<>();
        boolean failed = "failed".equals(observation.status());
        boolean waitingForUser = "waiting_for_user".equals(observation.status());
        boolean hasEvidence = !observation.evidence().isEmpty() || !observation.artifacts().isEmpty()
                || !observation.visibleObjects().isEmpty();
        boolean sufficient = !failed && !waitingForUser && observation.missingInputs().isEmpty()
                && (node.completionCriteria().isBlank() || observation.confidence() >= 0.5);
        boolean grounded = !requiresGrounding(capability) || hasEvidence;
        boolean usable = sufficient && grounded;
        if (failed) {
            reasons.add("Observation failed with error type " + observation.errorType() + ".");
        }
        if (waitingForUser || !observation.missingInputs().isEmpty()) {
            reasons.add("Observation is waiting for missing inputs " + observation.missingInputs() + ".");
        }
        if (!grounded) {
            reasons.add("Capability output needs evidence or artifacts but none were produced.");
        }
        if (!node.completionCriteria().isBlank() && observation.confidence() < 0.5) {
            reasons.add("Observation confidence is below the completion threshold.");
        }
        boolean needsFallback = failed && observation.retryable() && !capability.fallbackCapabilityNames().isEmpty();
        boolean needsReplan = failed && !observation.retryable() && capability.fallbackCapabilityNames().isEmpty();
        return new ObservationEvaluation(observation.id(), observation.taskNodeId(), observation.capabilityName(),
                sufficient, grounded, usable, needsFallback, needsReplan, reasons);
    }

    private boolean requiresGrounding(CapabilityDescriptor capability) {
        String type = capability.capabilityType().toLowerCase(java.util.Locale.ROOT);
        return type.contains("query") || type.contains("proposal") || capability.outputContract().stream()
                .anyMatch(field -> field.contains("evidence") || field.contains("summary")
                        || field.contains("rule") || field.contains("preview"));
    }
}
