package com.example.marketing.eval;

import java.util.List;

public record EvalCaseReport(
        String caseId,
        String datasetName,
        boolean passed,
        String answer,
        SystemTrace trace,
        List<EvalMetricResult> metrics
) {
    public EvalCaseReport {
        answer = answer == null ? "" : answer;
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
