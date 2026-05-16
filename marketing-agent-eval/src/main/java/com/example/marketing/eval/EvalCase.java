package com.example.marketing.eval;

import java.util.List;
import java.util.Map;

import com.example.marketing.api.MarketingRequest;

public record EvalCase(
        String id,
        String query,
        String expectedAction,
        String expectedSkillName,
        String expectedDelegateTo,
        List<String> mustContain,
        List<String> forbidden,
        Map<String, Object> variables
) {
    public MarketingRequest toRequest() {
        return new MarketingRequest("eval_" + id, "eval-user", query, "", "", "", List.of(),
                variables == null ? Map.of() : variables);
    }
}
