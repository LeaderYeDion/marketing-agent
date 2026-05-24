package com.example.marketing.core.observation;

import java.util.List;

public record ObservationEvaluation(
        String observationId,
        String taskNodeId,
        String workerName,
        boolean sufficient,
        boolean grounded,
        boolean usable,
        boolean needsFallback,
        boolean needsReplan,
        List<String> reasons
) {
    public ObservationEvaluation {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}

