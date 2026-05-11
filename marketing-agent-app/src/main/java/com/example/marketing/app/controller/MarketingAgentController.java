package com.example.marketing.app.controller;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.api.MarketingStreamEvent;
import com.example.marketing.api.StreamEventTypes;
import com.example.marketing.core.MarketingAgentService;

@RestController
@RequestMapping("/api/v1/marketing-agent")
public class MarketingAgentController {
    private final MarketingAgentService marketingAgentService;
    private final Executor agentTaskExecutor;

    public MarketingAgentController(MarketingAgentService marketingAgentService, Executor agentTaskExecutor) {
        this.marketingAgentService = marketingAgentService;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    @PostMapping("/chat")
    public MarketingResponse chat(@RequestBody MarketingRequest request) {
        return marketingAgentService.run(request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody MarketingRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        agentTaskExecutor.execute(() -> {
            try {
                MarketingResponse response = marketingAgentService.run(request, event -> send(emitter, event));
                send(emitter, MarketingStreamEvent.of(
                        response.conversationId(), StreamEventTypes.DONE, "sse", "响应已发送",
                        Map.of("response", response)));
                emitter.complete();
            }
            catch (Exception ex) {
                try {
                    send(emitter, MarketingStreamEvent.of(
                            request == null ? "unknown" : request.conversationId(),
                            StreamEventTypes.ERROR, "sse", ex.getMessage(), Map.of()));
                }
                finally {
                    emitter.completeWithError(ex);
                }
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, MarketingStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.conversationId() + "-" + event.timestamp().toEpochMilli())
                    .name(event.type())
                    .data(event));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to send SSE event", ex);
        }
    }
}
