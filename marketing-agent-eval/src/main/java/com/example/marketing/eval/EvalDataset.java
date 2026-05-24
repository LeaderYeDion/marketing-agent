package com.example.marketing.eval;

import java.util.List;

public record EvalDataset(
        String name,
        List<EvalCase> cases
) {
    public EvalDataset {
        name = name == null || name.isBlank() ? "default" : name;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
