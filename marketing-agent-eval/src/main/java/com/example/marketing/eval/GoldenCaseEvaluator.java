package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.List;

import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.MarketingAgentService;
import com.example.marketing.core.task.TaskGraph;

public class GoldenCaseEvaluator {
    private final MarketingAgentService marketingAgentService;

    public GoldenCaseEvaluator(MarketingAgentService marketingAgentService) {
        this.marketingAgentService = marketingAgentService;
    }

    public EvalSuiteResult evaluate(List<EvalCase> cases) {
        List<EvalResult> results = cases.stream().map(this::evaluate).toList();
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        return new EvalSuiteResult(results.size(), passed, results);
    }

    public EvalResult evaluate(EvalCase evalCase) {
        MarketingResponse response = marketingAgentService.run(evalCase.toRequest());
        List<String> failures = new ArrayList<>();
        assertContains(response.answer(), evalCase.mustContain(), failures, "answer missing");
        assertForbidden(response.answer(), evalCase.forbidden(), failures);
        String decision = String.valueOf(response.metadata().get("decision"));
        if (!evalCase.expectedAction().isBlank() && !decision.contains(evalCase.expectedAction())) {
            failures.add("expected action " + evalCase.expectedAction() + " but decision was " + decision);
        }
        if (!evalCase.expectedSkillName().isBlank() && !decision.contains(evalCase.expectedSkillName())) {
            failures.add("expected skill " + evalCase.expectedSkillName() + " but decision was " + decision);
        }
        if (!evalCase.expectedDelegateTo().isBlank() && !decision.contains(evalCase.expectedDelegateTo())) {
            failures.add("expected delegate " + evalCase.expectedDelegateTo() + " but decision was " + decision);
        }
        String taskGraphText = String.valueOf(response.metadata().get("taskGraph"));
        if (evalCase.expectedWorkers() != null) {
            for (String worker : evalCase.expectedWorkers()) {
                if (!worker.isBlank() && !taskGraphText.contains(worker) && !decision.contains(worker)) {
                    failures.add("expected worker " + worker + " but task graph was " + taskGraphText);
                }
            }
        }
        if (!evalCase.expectedHarnessStatus().isBlank()) {
            String harness = String.valueOf(response.metadata().get("harness"));
            if (!harness.contains(evalCase.expectedHarnessStatus())) {
                failures.add("expected harness status " + evalCase.expectedHarnessStatus() + " but harness was "
                        + harness);
            }
        }
        if (evalCase.minTaskNodes() > 0) {
            Object taskGraph = response.metadata().get("taskGraph");
            int nodeCount = nodeCount(taskGraph);
            if (nodeCount < evalCase.minTaskNodes()) {
                failures.add("expected at least " + evalCase.minTaskNodes() + " task nodes but got " + nodeCount);
            }
        }
        return failures.isEmpty() ? EvalResult.pass(evalCase.id()) : EvalResult.fail(evalCase.id(), failures);
    }

    private int nodeCount(Object taskGraph) {
        if (taskGraph instanceof TaskGraph graph) {
            return graph.nodes().size();
        }
        if (taskGraph instanceof java.util.Map<?, ?> map) {
            Object nodes = map.get("nodes");
            if (nodes instanceof java.util.Collection<?> collection) {
                return collection.size();
            }
        }
        return 0;
    }

    private void assertContains(String text, List<String> expected, List<String> failures, String prefix) {
        for (String item : expected) {
            if (!text.contains(item)) {
                failures.add(prefix + ": " + item);
            }
        }
    }

    private void assertForbidden(String text, List<String> forbidden, List<String> failures) {
        for (String item : forbidden) {
            if (text.contains(item)) {
                failures.add("answer contains forbidden text: " + item);
            }
        }
    }
}

