package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.List;

import com.example.marketing.api.MarketingResponse;

public class EvalRunner {
    private final EvalSystemRunner systemRunner;
    private final SystemTraceCapture traceCapture;
    private final List<MetricEvaluator> defaultEvaluators;

    public EvalRunner(EvalSystemRunner systemRunner) {
        this(systemRunner, new SystemTraceCapture(), defaultEvaluators());
    }

    public EvalRunner(EvalSystemRunner systemRunner,
                      SystemTraceCapture traceCapture,
                      List<MetricEvaluator> defaultEvaluators) {
        this.systemRunner = systemRunner;
        this.traceCapture = traceCapture == null ? new SystemTraceCapture() : traceCapture;
        this.defaultEvaluators = defaultEvaluators == null ? defaultEvaluators() : List.copyOf(defaultEvaluators);
    }

    public static List<MetricEvaluator> defaultEvaluators() {
        return List.of(
                new PlannerMetricEvaluator(),
                new WorkerMetricEvaluator(),
                new RagMetricEvaluator(),
                new EndToEndMetricEvaluator()
        );
    }

    public EvalReport run(EvalSuite suite) {
        List<EvalCaseReport> caseReports = new ArrayList<>();
        List<MetricEvaluator> evaluators = suite.evaluators().isEmpty() ? defaultEvaluators : suite.evaluators();
        for (EvalDataset dataset : suite.datasets()) {
            for (EvalCase evalCase : dataset.cases()) {
                caseReports.add(runCase(dataset.name(), evalCase, evaluators));
            }
        }
        int passed = (int) caseReports.stream().filter(EvalCaseReport::passed).count();
        return new EvalReport(suite.name(), caseReports.size(), passed, caseReports);
    }

    public EvalReport run(EvalDataset dataset) {
        return run(new EvalSuite(dataset.name(), List.of(dataset), defaultEvaluators));
    }

    public EvalCaseReport runCase(String datasetName, EvalCase evalCase, List<MetricEvaluator> evaluators) {
        MarketingResponse response = systemRunner.run(evalCase);
        SystemTrace trace = traceCapture.capture(response);
        EvalRun run = new EvalRun(evalCase, response, trace);
        List<EvalMetricResult> metrics = evaluators.stream()
                .map(evaluator -> evaluator.evaluate(evalCase, run))
                .toList();
        boolean passed = metrics.stream().allMatch(EvalMetricResult::passed);
        return new EvalCaseReport(evalCase.id(), datasetName, passed, response.answer(), trace, metrics);
    }
}
