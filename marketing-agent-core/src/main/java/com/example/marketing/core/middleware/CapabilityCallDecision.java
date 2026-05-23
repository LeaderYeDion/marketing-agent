package com.example.marketing.core.middleware;

import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.policy.RiskAssessment;

public record CapabilityCallDecision(
        boolean allowed,
        Observation observation,
        RiskAssessment riskAssessment
) {
    public static CapabilityCallDecision proceed() {
        return new CapabilityCallDecision(true, null, null);
    }

    public static CapabilityCallDecision proceed(RiskAssessment riskAssessment) {
        return new CapabilityCallDecision(true, null, riskAssessment);
    }

    public static CapabilityCallDecision pause(Observation observation, RiskAssessment riskAssessment) {
        return new CapabilityCallDecision(false, observation, riskAssessment);
    }

    public CapabilityCallDecision merge(CapabilityCallDecision other) {
        if (other == null) {
            return this;
        }
        if (other.allowed()) {
            return other.riskAssessment() == null ? this : other;
        }
        return other;
    }
}
