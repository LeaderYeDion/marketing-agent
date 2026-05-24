package com.example.marketing.eval;

import java.util.List;

public record EvalReport(
        String suiteName,
        int total,
        int passed,
        List<EvalCaseReport> cases
) {
    public EvalReport {
        suiteName = suiteName == null || suiteName.isBlank() ? "default" : suiteName;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public double passRate() {
        return total == 0 ? 1.0 : (double) passed / total;
    }

    public List<EvalMetricResult> metricResults() {
        return cases.stream().flatMap(item -> item.metrics().stream()).toList();
    }
}
