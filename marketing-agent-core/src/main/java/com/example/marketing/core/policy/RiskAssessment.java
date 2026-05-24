package com.example.marketing.core.policy;

public record RiskAssessment(
        String workerName,
        String riskLevel,
        boolean requiresApproval,
        boolean readOnly,
        String reason
) {
}

