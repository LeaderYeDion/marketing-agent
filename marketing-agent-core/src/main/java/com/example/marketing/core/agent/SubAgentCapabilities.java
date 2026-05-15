package com.example.marketing.core.agent;

import java.util.Set;

public record SubAgentCapabilities(
        boolean supportsHitl,
        boolean hasSideEffects,
        Set<String> requiredInputs
) {
    public static SubAgentCapabilities stateless(Set<String> requiredInputs) {
        return new SubAgentCapabilities(false, false, requiredInputs == null ? Set.of() : Set.copyOf(requiredInputs));
    }

    public static SubAgentCapabilities hitlWithSideEffects(Set<String> requiredInputs) {
        return new SubAgentCapabilities(true, true, requiredInputs == null ? Set.of() : Set.copyOf(requiredInputs));
    }
}
