package com.example.marketing.core.policy;

public record RiskAssessment(
        String capabilityName,
        String riskLevel,
        boolean requiresApproval,
        boolean readOnly,
        String reason
) {
}
