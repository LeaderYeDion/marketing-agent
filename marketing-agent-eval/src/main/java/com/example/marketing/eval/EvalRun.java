package com.example.marketing.eval;

import com.example.marketing.api.MarketingResponse;

public record EvalRun(
        EvalCase evalCase,
        MarketingResponse response,
        SystemTrace trace
) {
}
