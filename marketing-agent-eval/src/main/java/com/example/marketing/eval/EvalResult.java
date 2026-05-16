package com.example.marketing.eval;

import java.util.List;

public record EvalResult(
        String caseId,
        boolean passed,
        List<String> failures
) {
    public static EvalResult pass(String caseId) {
        return new EvalResult(caseId, true, List.of());
    }

    public static EvalResult fail(String caseId, List<String> failures) {
        return new EvalResult(caseId, false, failures == null ? List.of() : List.copyOf(failures));
    }
}
