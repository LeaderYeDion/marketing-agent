package com.example.marketing.eval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.marketing.api.MarketingRequest;

public record EvalCase(
        String id,
        String query,
        String expectedAction,
        String expectedSkillName,
        String expectedDelegateTo,
        List<String> expectedWorkers,
        List<String> forbiddenWorkers,
        Map<String, List<String>> expectedDependencies,
        String expectedDelegateAgent,
        List<String> expectedMissingInputs,
        String expectedHarnessStatus,
        int minTaskNodes,
        Map<String, String> expectedObservationStatuses,
        List<String> expectedEvidenceKeys,
        List<String> expectedArtifactKeys,
        List<String> expectedWorkspaceRefs,
        List<String> goldEvidenceIds,
        List<String> goldAnswerFacts,
        List<String> forbiddenFacts,
        List<String> expectedCitationPaths,
        List<String> mustContain,
        List<String> forbidden,
        Map<String, Object> variables
) {
    public EvalCase {
        id = id == null ? "" : id;
        query = query == null ? "" : query;
        expectedAction = expectedAction == null ? "" : expectedAction;
        expectedSkillName = expectedSkillName == null ? "" : expectedSkillName;
        expectedDelegateTo = expectedDelegateTo == null ? "" : expectedDelegateTo;
        expectedWorkers = expectedWorkers == null ? List.of() : List.copyOf(expectedWorkers);
        forbiddenWorkers = forbiddenWorkers == null ? List.of() : List.copyOf(forbiddenWorkers);
        expectedDependencies = expectedDependencies == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(expectedDependencies));
        expectedDelegateAgent = expectedDelegateAgent == null ? "" : expectedDelegateAgent;
        expectedMissingInputs = expectedMissingInputs == null ? List.of() : List.copyOf(expectedMissingInputs);
        expectedHarnessStatus = expectedHarnessStatus == null ? "" : expectedHarnessStatus;
        expectedObservationStatuses = expectedObservationStatuses == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(expectedObservationStatuses));
        expectedEvidenceKeys = expectedEvidenceKeys == null ? List.of() : List.copyOf(expectedEvidenceKeys);
        expectedArtifactKeys = expectedArtifactKeys == null ? List.of() : List.copyOf(expectedArtifactKeys);
        expectedWorkspaceRefs = expectedWorkspaceRefs == null ? List.of() : List.copyOf(expectedWorkspaceRefs);
        goldEvidenceIds = goldEvidenceIds == null ? List.of() : List.copyOf(goldEvidenceIds);
        goldAnswerFacts = goldAnswerFacts == null ? List.of() : List.copyOf(goldAnswerFacts);
        forbiddenFacts = forbiddenFacts == null ? List.of() : List.copyOf(forbiddenFacts);
        expectedCitationPaths = expectedCitationPaths == null ? List.of() : List.copyOf(expectedCitationPaths);
        mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
        forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public EvalCase(String id,
                    String query,
                    String expectedAction,
                    String expectedSkillName,
                    String expectedDelegateTo,
                    List<String> expectedWorkers,
                    String expectedHarnessStatus,
                    int minTaskNodes,
                    List<String> mustContain,
                    List<String> forbidden,
                    Map<String, Object> variables) {
        this(id, query, expectedAction, expectedSkillName, expectedDelegateTo, expectedWorkers, List.of(), Map.of(),
                "", List.of(), expectedHarnessStatus, minTaskNodes, Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), mustContain, forbidden, variables);
    }

    public MarketingRequest toRequest() {
        return new MarketingRequest("eval_" + id, "eval-user", query, "", "", "", List.of(),
                variables);
    }
}
