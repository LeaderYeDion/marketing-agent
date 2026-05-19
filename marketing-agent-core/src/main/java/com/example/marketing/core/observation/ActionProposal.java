package com.example.marketing.core.observation;

import java.util.Map;

public record ActionProposal(
        String id,
        String type,
        String sourceCapability,
        String summary,
        Map<String, Object> payload,
        String riskLevel
) {
    public ActionProposal {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        sourceCapability = sourceCapability == null ? "" : sourceCapability;
        summary = summary == null ? "" : summary;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        riskLevel = riskLevel == null ? "medium" : riskLevel;
    }
}
