package com.example.marketing.core.edge;

import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.example.marketing.core.state.MarketingStateKeys;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

public final class MarketingEdges {
    private MarketingEdges() {
    }

    public static AsyncEdgeAction routeAfterIntent() {
        return edge_async(state -> state.value(MarketingStateKeys.NEXT)
                .map(Object::toString)
                .orElse("continue"));
    }
}
