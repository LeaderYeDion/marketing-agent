package com.example.marketing.core.middleware;

import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.policy.RiskAssessment;

public record WorkerCallDecision(
        boolean allowed,
        Observation observation,
        RiskAssessment riskAssessment
) {
    public static WorkerCallDecision proceed() {
        return new WorkerCallDecision(true, null, null);
    }

    public static WorkerCallDecision proceed(RiskAssessment riskAssessment) {
        return new WorkerCallDecision(true, null, riskAssessment);
    }

    public static WorkerCallDecision pause(Observation observation, RiskAssessment riskAssessment) {
        return new WorkerCallDecision(false, observation, riskAssessment);
    }

    public WorkerCallDecision merge(WorkerCallDecision other) {
        if (other == null) {
            return this;
        }
        if (other.allowed()) {
            return other.riskAssessment() == null ? this : other;
        }
        return other;
    }
}

