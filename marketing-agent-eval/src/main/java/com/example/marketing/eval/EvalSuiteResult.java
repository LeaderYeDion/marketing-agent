package com.example.marketing.eval;

import java.util.List;

public record EvalSuiteResult(
        int total,
        int passed,
        List<EvalResult> results
) {
    public double passRate() {
        return total == 0 ? 1.0 : (double) passed / total;
    }
}
