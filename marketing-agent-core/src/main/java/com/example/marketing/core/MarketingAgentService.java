package com.example.marketing.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.api.MarketingStreamEvent;
import com.example.marketing.api.MarketingStreamListener;
import com.example.marketing.api.StreamEventTypes;
import com.example.marketing.core.context.MarketingEventPublisher;
import com.example.marketing.core.graph.MarketingGraphFactory;
import com.example.marketing.core.observability.AgentRuntimeContext;
import com.example.marketing.core.observability.AgentRuntimeContextHolder;
import com.example.marketing.core.observability.AgentTelemetry;
import com.example.marketing.core.state.MarketingStateKeys;

@Service
public class MarketingAgentService {
    private final MarketingGraphFactory graphFactory;
    private final AgentTelemetry telemetry;

    public MarketingAgentService(MarketingGraphFactory graphFactory, AgentTelemetry telemetry) {
        this.graphFactory = graphFactory;
        this.telemetry = telemetry;
    }

    public MarketingResponse run(MarketingRequest request) {
        return run(request, event -> {
        });
    }

    public MarketingResponse run(MarketingRequest request, MarketingStreamListener streamListener) {
        MarketingRequest normalizedRequest = normalize(request);
        streamListener.onEvent(MarketingStreamEvent.of(
                normalizedRequest.conversationId(), StreamEventTypes.START, "graph", "营销助手开始处理", Map.of()));
        try {
            ResultHolder resultHolder = new ResultHolder();
            AgentRuntimeContext runtimeContext = AgentRuntimeContext.create(normalizedRequest.conversationId(),
                    normalizedRequest.userId());
            AgentRuntimeContextHolder.withContext(runtimeContext, () ->
                    MarketingEventPublisher.withListener(streamListener, () ->
                            resultHolder.response = telemetry.timed("agent_request", "graph", () -> {
                                CompiledGraph graph = graphFactory.createGraph();
                                Map<String, Object> initialState = new HashMap<>();
                                initialState.put(MarketingStateKeys.REQUEST, normalizedRequest);
                                OverAllState state = graph.invoke(initialState, RunnableConfig.builder()
                                                .threadId(normalizedRequest.conversationId())
                                                .build())
                                        .orElseThrow(() -> new IllegalStateException("Graph returned empty state"));
                                return toResponse(normalizedRequest, state);
                            })));
            MarketingResponse response = resultHolder.response;
            streamListener.onEvent(MarketingStreamEvent.of(
                    normalizedRequest.conversationId(), StreamEventTypes.DONE, "graph", "营销助手处理完成",
                    Map.of("conversationId", normalizedRequest.conversationId())));
            return response;
        }
        catch (RuntimeException ex) {
            streamListener.onEvent(MarketingStreamEvent.of(
                    normalizedRequest.conversationId(), StreamEventTypes.ERROR, "graph", ex.getMessage(), Map.of()));
            throw ex;
        }
    }

    private MarketingRequest normalize(MarketingRequest request) {
        if (request == null) {
            request = new MarketingRequest(null, null, null, null, null, null, List.of(), Map.of());
        }
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        return new MarketingRequest(
                conversationId,
                request.userId(),
                request.query() == null ? "" : request.query(),
                request.channel(),
                request.product(),
                request.audience(),
                request.goals() == null ? List.of() : request.goals(),
                request.variables() == null ? Map.of() : request.variables()
        );
    }

    private MarketingResponse toResponse(MarketingRequest request, OverAllState state) {
        return state.value(MarketingStateKeys.RESPONSE)
                .filter(MarketingResponse.class::isInstance)
                .map(MarketingResponse.class::cast)
                .orElseGet(() -> new MarketingResponse(request.conversationId(), "", List.of(), List.of(), Map.of()));
    }

    private static final class ResultHolder {
        private MarketingResponse response;
    }
}
