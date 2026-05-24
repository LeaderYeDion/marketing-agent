package com.example.marketing.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RagMetricEvaluator implements MetricEvaluator {
    @Override
    public EvalLayer layer() {
        return EvalLayer.RAG;
    }

    @Override
    public String name() {
        return "rag_grounding";
    }

    @Override
    public EvalMetricResult evaluate(EvalCase evalCase, EvalRun run) {
        SystemTrace trace = run.trace();
        List<String> failures = new ArrayList<>();
        String retrievedText = trace.ragRetrievedChunks().toString() + " " + trace.evidence();
        for (String evidenceId : evalCase.goldEvidenceIds()) {
            if (!retrievedText.contains(evidenceId)) {
                failures.add("gold evidence was not retrieved: " + evidenceId);
            }
        }
        for (String path : evalCase.expectedCitationPaths()) {
            if (!retrievedText.contains(path) && !run.response().answer().contains(path)) {
                failures.add("expected citation path missing: " + path);
            }
        }
        for (String fact : evalCase.goldAnswerFacts()) {
            if (!run.response().answer().contains(fact) && !retrievedText.contains(fact)) {
                failures.add("gold answer fact is not grounded in answer or evidence: " + fact);
            }
        }
        for (String fact : evalCase.forbiddenFacts()) {
            if (run.response().answer().contains(fact)) {
                failures.add("answer contains forbidden fact: " + fact);
            }
        }
        return failures.isEmpty()
                ? EvalMetricResult.pass(layer(), name(), details(trace))
                : EvalMetricResult.fail(layer(), name(), failures, details(trace));
    }

    private Map<String, Object> details(SystemTrace trace) {
        int goldLikeHits = trace.ragRetrievedChunks().size();
        return Map.of(
                "retrievedChunkCount", trace.ragRetrievedChunks().size(),
                "evidenceItemCount", trace.evidence().size(),
                "hitRateProxy", goldLikeHits == 0 ? 0.0 : 1.0
        );
    }
}
