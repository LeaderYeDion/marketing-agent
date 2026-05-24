package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WorkerMetricEvaluator implements MetricEvaluator {
    @Override
    public EvalLayer layer() {
        return EvalLayer.WORKER;
    }

    @Override
    public String name() {
        return "worker_contract";
    }

    @Override
    public EvalMetricResult evaluate(EvalCase evalCase, EvalRun run) {
        SystemTrace trace = run.trace();
        List<String> failures = new ArrayList<>();
        verifyObservationStatuses(evalCase, trace.observations(), failures);
        verifyKeys("evidence", evalCase.expectedEvidenceKeys(), trace.evidence(), failures);
        verifyKeys("artifact", evalCase.expectedArtifactKeys(), trace.artifacts(), failures);
        verifyWorkspaceRefs(evalCase, trace, failures);
        verifyHumanApprovalBoundaries(trace, failures);
        return failures.isEmpty()
                ? EvalMetricResult.pass(layer(), name(), details(trace))
                : EvalMetricResult.fail(layer(), name(), failures, details(trace));
    }

    private void verifyObservationStatuses(EvalCase evalCase, List<Map<String, Object>> observations,
                                           List<String> failures) {
        evalCase.expectedObservationStatuses().forEach((worker, expectedStatus) -> {
            boolean matched = observations.stream()
                    .anyMatch(observation -> worker.equals(SystemTraceCapture.stringValue(
                            observation.get("workerName")))
                            && expectedStatus.equals(SystemTraceCapture.stringValue(observation.get("status"))));
            if (!matched) {
                failures.add("expected observation status " + expectedStatus + " for worker " + worker);
            }
        });
    }

    private void verifyKeys(String label, List<String> expectedKeys, List<Map<String, Object>> values,
                            List<String> failures) {
        String joined = values.toString();
        for (String key : expectedKeys) {
            if (!joined.contains(key)) {
                failures.add("expected " + label + " key " + key);
            }
        }
    }

    private void verifyWorkspaceRefs(EvalCase evalCase, SystemTrace trace, List<String> failures) {
        String workspaceText = trace.workspaceRefs() + " " + trace.workspaceEntries();
        for (String ref : evalCase.expectedWorkspaceRefs()) {
            if (!workspaceText.contains(ref)) {
                failures.add("expected recoverable workspace ref " + ref);
            }
        }
    }

    private void verifyHumanApprovalBoundaries(SystemTrace trace, List<String> failures) {
        for (Map<String, Object> worker : trace.workers()) {
            if (!Boolean.TRUE.equals(worker.get("requiresHumanApproval"))) {
                continue;
            }
            String workerName = SystemTraceCapture.stringValue(worker.get("name"));
            boolean selected = trace.workerSequence().contains(workerName);
            if (!selected) {
                continue;
            }
            boolean hasWaitingObservation = trace.observations().stream()
                    .filter(observation -> workerName.equals(SystemTraceCapture.stringValue(
                            observation.get("workerName"))))
                    .anyMatch(observation -> Boolean.TRUE.equals(observation.get("requiresApproval"))
                            || SystemTraceCapture.stringValue(observation.get("status")).contains("approval"));
            boolean hasRiskAssessment = trace.riskAssessments().stream()
                    .anyMatch(risk -> workerName.equals(SystemTraceCapture.stringValue(risk.get("workerName")))
                            && Boolean.TRUE.equals(risk.get("requiresApproval")));
            if (!hasWaitingObservation && !hasRiskAssessment && trace.pendingActions().isEmpty()) {
                failures.add("side-effect worker " + workerName + " did not expose HITL approval trace");
            }
        }
    }

    private Map<String, Object> details(SystemTrace trace) {
        return Map.of(
                "observationCount", trace.observations().size(),
                "workspaceRefCount", trace.workspaceRefs().size(),
                "pendingActionCount", trace.pendingActions().size()
        );
    }
}
