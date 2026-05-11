package com.example.marketing.core.agent;

import java.util.Map;

public record MainAgentDecision(
        String action,
        String skillName,
        String delegateTo,
        String reply,
        Map<String, Object> inputs,
        String compressedContext
) {
}
