package com.example.marketing.eval;

import java.util.List;

public record EvalSuite(
        String name,
        List<EvalDataset> datasets,
        List<MetricEvaluator> evaluators
) {
    public EvalSuite {
        name = name == null || name.isBlank() ? "default" : name;
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        evaluators = evaluators == null ? EvalRunner.defaultEvaluators() : List.copyOf(evaluators);
    }

    public static EvalSuite golden(List<EvalCase> cases) {
        return new EvalSuite("golden", List.of(new EvalDataset("golden-cases", cases)),
                EvalRunner.defaultEvaluators());
    }
}
