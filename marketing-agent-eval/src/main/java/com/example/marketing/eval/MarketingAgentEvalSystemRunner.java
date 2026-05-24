package com.example.marketing.eval;

import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.MarketingAgentService;

public class MarketingAgentEvalSystemRunner implements EvalSystemRunner {
    private final MarketingAgentService marketingAgentService;

    public MarketingAgentEvalSystemRunner(MarketingAgentService marketingAgentService) {
        this.marketingAgentService = marketingAgentService;
    }

    @Override
    public MarketingResponse run(EvalCase evalCase) {
        return marketingAgentService.run(evalCase.toRequest());
    }
}
