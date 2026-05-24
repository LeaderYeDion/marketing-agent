package com.example.marketing.eval;

public interface MetricEvaluator {
    EvalLayer layer();

    String name();

    EvalMetricResult evaluate(EvalCase evalCase, EvalRun run);
}
