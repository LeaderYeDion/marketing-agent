package com.example.marketing.core.agent;

import java.util.Set;

public record SubAgentWorkers(
        boolean supportsHitl,
        boolean hasSideEffects,
        Set<String> requiredInputs
) {
    public static SubAgentWorkers stateless(Set<String> requiredInputs) {
        return new SubAgentWorkers(false, false, requiredInputs == null ? Set.of() : Set.copyOf(requiredInputs));
    }

    public static SubAgentWorkers hitlWithSideEffects(Set<String> requiredInputs) {
        return new SubAgentWorkers(true, true, requiredInputs == null ? Set.of() : Set.copyOf(requiredInputs));
    }
}
