package com.example.marketing.eval;

import java.util.List;

import com.example.marketing.core.MarketingAgentService;

public class GoldenCaseEvaluator {
    private final EvalRunner evalRunner;

    public GoldenCaseEvaluator(MarketingAgentService marketingAgentService) {
        this(new EvalRunner(new MarketingAgentEvalSystemRunner(marketingAgentService)));
    }

    public GoldenCaseEvaluator(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    public EvalSuiteResult evaluate(List<EvalCase> cases) {
        EvalReport report = evalRunner.run(EvalSuite.golden(cases));
        List<EvalResult> results = report.cases().stream()
                .map(caseReport -> caseReport.passed()
                        ? EvalResult.pass(caseReport.caseId())
                        : EvalResult.fail(caseReport.caseId(), failures(caseReport)))
                .toList();
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        return new EvalSuiteResult(results.size(), passed, results);
    }

    public EvalResult evaluate(EvalCase evalCase) {
        EvalCaseReport report = evalRunner.runCase("golden-cases", evalCase, EvalRunner.defaultEvaluators());
        return report.passed() ? EvalResult.pass(evalCase.id()) : EvalResult.fail(evalCase.id(), failures(report));
    }

    private List<String> failures(EvalCaseReport report) {
        return report.metrics().stream()
                .filter(metric -> !metric.passed())
                .flatMap(metric -> metric.failures().stream()
                        .map(failure -> metric.layer() + "/" + metric.metricName() + ": " + failure))
                .toList();
    }
}

