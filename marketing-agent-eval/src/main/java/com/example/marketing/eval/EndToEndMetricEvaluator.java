package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EndToEndMetricEvaluator implements MetricEvaluator {
    @Override
    public EvalLayer layer() {
        return EvalLayer.END_TO_END;
    }

    @Override
    public String name() {
        return "end_to_end_goal";
    }

    @Override
    public EvalMetricResult evaluate(EvalCase evalCase, EvalRun run) {
        List<String> failures = new ArrayList<>();
        String answer = run.response().answer() == null ? "" : run.response().answer();
        for (String item : evalCase.mustContain()) {
            if (!answer.contains(item)) {
                failures.add("answer missing: " + item);
            }
        }
        for (String item : evalCase.forbidden()) {
            if (answer.contains(item)) {
                failures.add("answer contains forbidden text: " + item);
            }
        }
        if (!evalCase.expectedHarnessStatus().isBlank()) {
            String status = SystemTraceCapture.stringValue(run.trace().harness().get("status"));
            if (!evalCase.expectedHarnessStatus().equals(status)) {
                failures.add("expected final harness status " + evalCase.expectedHarnessStatus() + " but was "
                        + status);
            }
        }
        return failures.isEmpty()
                ? EvalMetricResult.pass(layer(), name(), details(run))
                : EvalMetricResult.fail(layer(), name(), failures, details(run));
    }

    private Map<String, Object> details(EvalRun run) {
        return Map.of(
                "answerLength", run.response().answer() == null ? 0 : run.response().answer().length(),
                "finalStatus", SystemTraceCapture.stringValue(run.trace().harness().get("status")),
                "workerSequence", run.trace().workerSequence()
        );
    }
}
