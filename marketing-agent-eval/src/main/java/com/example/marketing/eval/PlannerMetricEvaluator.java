package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlannerMetricEvaluator implements MetricEvaluator {
    @Override
    public EvalLayer layer() {
        return EvalLayer.PLANNER;
    }

    @Override
    public String name() {
        return "planner_contract";
    }

    @Override
    public EvalMetricResult evaluate(EvalCase evalCase, EvalRun run) {
        SystemTrace trace = run.trace();
        List<String> failures = new ArrayList<>();
        String decisionText = trace.plannerOutput().toString();
        if (!evalCase.expectedAction().isBlank() && !decisionText.contains(evalCase.expectedAction())) {
            failures.add("expected planner action " + evalCase.expectedAction());
        }
        if (!evalCase.expectedSkillName().isBlank() && !decisionText.contains(evalCase.expectedSkillName())) {
            failures.add("expected planner/provider trace to include " + evalCase.expectedSkillName());
        }
        if (!evalCase.expectedDelegateTo().isBlank() && !decisionText.contains(evalCase.expectedDelegateTo())) {
            failures.add("expected planner/provider trace to include delegate target " + evalCase.expectedDelegateTo());
        }
        for (String worker : evalCase.expectedWorkers()) {
            if (!trace.workerSequence().contains(worker)) {
                failures.add("expected worker " + worker + " in worker sequence " + trace.workerSequence());
            }
        }
        for (String worker : evalCase.forbiddenWorkers()) {
            if (trace.workerSequence().contains(worker)) {
                failures.add("forbidden worker selected: " + worker);
            }
        }
        if (!evalCase.expectedHarnessStatus().isBlank()) {
            String status = SystemTraceCapture.stringValue(trace.harness().get("status"));
            if (!evalCase.expectedHarnessStatus().equals(status)) {
                failures.add("expected harness status " + evalCase.expectedHarnessStatus() + " but was " + status);
            }
        }
        List<Map<String, Object>> nodes = SystemTraceCapture.taskNodes(trace.taskGraph());
        if (evalCase.minTaskNodes() > 0 && nodes.size() < evalCase.minTaskNodes()) {
            failures.add("expected at least " + evalCase.minTaskNodes() + " task nodes but got " + nodes.size());
        }
        verifyDependencies(evalCase, nodes, failures);
        verifyDelegateAgent(evalCase, nodes, failures);
        verifyMissingInputs(evalCase, trace.observations(), failures);
        return failures.isEmpty()
                ? EvalMetricResult.pass(layer(), name(), details(trace, nodes))
                : EvalMetricResult.fail(layer(), name(), failures, details(trace, nodes));
    }

    private void verifyDependencies(EvalCase evalCase, List<Map<String, Object>> nodes, List<String> failures) {
        if (evalCase.expectedDependencies().isEmpty()) {
            return;
        }
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            actual.put(SystemTraceCapture.stringValue(node.get("id")),
                    SystemTraceCapture.stringList(node.get("dependsOn")));
        }
        evalCase.expectedDependencies().forEach((nodeId, dependencies) -> {
            List<String> actualDependencies = actual.getOrDefault(nodeId, List.of());
            for (String dependency : dependencies) {
                if (!actualDependencies.contains(dependency)) {
                    failures.add("expected dependency " + nodeId + " -> " + dependency);
                }
            }
        });
    }

    private void verifyDelegateAgent(EvalCase evalCase, List<Map<String, Object>> nodes, List<String> failures) {
        if (evalCase.expectedDelegateAgent().isBlank()) {
            return;
        }
        boolean matched = nodes.stream()
                .filter(node -> "delegate_task".equals(SystemTraceCapture.stringValue(node.get("workerName"))))
                .map(node -> SystemTraceCapture.map(node.get("inputs")))
                .anyMatch(inputs -> evalCase.expectedDelegateAgent().equals(SystemTraceCapture.stringValue(
                        inputs.get("agentName"))));
        if (!matched) {
            failures.add("expected delegate_task agentName " + evalCase.expectedDelegateAgent());
        }
    }

    private void verifyMissingInputs(EvalCase evalCase, List<Map<String, Object>> observations,
                                     List<String> failures) {
        for (String input : evalCase.expectedMissingInputs()) {
            boolean matched = observations.stream()
                    .flatMap(observation -> SystemTraceCapture.stringList(observation.get("missingInputs")).stream())
                    .anyMatch(input::equals);
            if (!matched) {
                failures.add("expected missing input " + input);
            }
        }
    }

    private Map<String, Object> details(SystemTrace trace, List<Map<String, Object>> nodes) {
        return Map.of(
                "workerSequence", trace.workerSequence(),
                "taskNodeCount", nodes.size(),
                "harnessStatus", SystemTraceCapture.stringValue(trace.harness().get("status"))
        );
    }
}
