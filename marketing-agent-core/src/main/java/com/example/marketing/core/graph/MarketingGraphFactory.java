package com.example.marketing.core.graph;

import java.util.HashMap;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.marketing.core.agent.AgentOrchestrator;
import com.example.marketing.core.node.MarketingNodeNames;
import com.example.marketing.core.state.MarketingStateKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class MarketingGraphFactory {
    private final AgentOrchestrator agentOrchestrator;

    public MarketingGraphFactory(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    public CompiledGraph createGraph() {
        try {
            return new StateGraph(keyStrategyFactory())
                    .addNode(MarketingNodeNames.HARNESS, node_async(state -> java.util.Map.of(
                            MarketingStateKeys.RESPONSE,
                            agentOrchestrator.run(state.value(MarketingStateKeys.REQUEST)
                                    .filter(com.example.marketing.api.MarketingRequest.class::isInstance)
                                    .map(com.example.marketing.api.MarketingRequest.class::cast)
                                    .orElseThrow(() -> new IllegalStateException("Missing request")))
                    )))
                    .addEdge(START, MarketingNodeNames.HARNESS)
                    .addEdge(MarketingNodeNames.HARNESS, END)
                    .compile();
        }
        catch (GraphStateException ex) {
            throw new IllegalStateException("Failed to compile marketing graph", ex);
        }
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(MarketingStateKeys.REQUEST, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.RESPONSE, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.CONTEXT, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.INTENT, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.RETRIEVED_DOCUMENTS, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.PLAN, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.ANSWER, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.SUGGESTIONS, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.NEXT, new ReplaceStrategy());
            strategies.put(MarketingStateKeys.TRACE, new AppendStrategy());
            return strategies;
        };
    }
}
