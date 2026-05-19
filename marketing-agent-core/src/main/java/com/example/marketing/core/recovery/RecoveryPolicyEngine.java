package com.example.marketing.core.recovery;

import org.springframework.stereotype.Service;

import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.observation.Observation;

@Service
public class RecoveryPolicyEngine {
    public RecoveryDecision decide(CapabilityDescriptor capability, Observation observation) {
        if (!"failed".equals(observation.status()) && !"waiting_for_user".equals(observation.status())) {
            return RecoveryDecision.none("Observation is usable.");
        }
        if ("waiting_for_user".equals(observation.status())) {
            return RecoveryDecision.askUser("Required inputs are missing: " + observation.missingInputs());
        }
        if (observation.retryable() && !capability.fallbackCapabilityNames().isEmpty()) {
            return RecoveryDecision.fallback(capability.fallbackCapabilityNames().getFirst(),
                    "Primary capability failed with a retryable error.");
        }
        return RecoveryDecision.none("No configured recovery path is available.");
    }
}
