package com.example.marketing.eval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvalMetricResult(
        EvalLayer layer,
        String metricName,
        boolean passed,
        double score,
        List<String> failures,
        Map<String, Object> details
) {
    public EvalMetricResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static EvalMetricResult pass(EvalLayer layer, String metricName, Map<String, Object> details) {
        return new EvalMetricResult(layer, metricName, true, 1.0, List.of(), details);
    }

    public static EvalMetricResult fail(EvalLayer layer, String metricName, List<String> failures,
                                        Map<String, Object> details) {
        return new EvalMetricResult(layer, metricName, false, 0.0, failures, details);
    }
}
