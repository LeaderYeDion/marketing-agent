package com.example.marketing.eval;

import com.example.marketing.api.MarketingResponse;

public interface EvalSystemRunner {
    MarketingResponse run(EvalCase evalCase);
}
