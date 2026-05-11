package com.example.marketing.core.agent;

import java.util.Map;

import com.example.marketing.api.MarketingStreamEvent;
import com.example.marketing.api.StreamEventTypes;
import com.example.marketing.core.context.MarketingEventPublisher;

public class SubAgentEventSink {
    private final String conversationId;
    private final String invocationId;
    private final String source;

    public SubAgentEventSink(String conversationId, String invocationId, String source) {
        this.conversationId = conversationId;
        this.invocationId = invocationId;
        this.source = source;
    }

    public void token(String content) {
        publish(StreamEventTypes.TOKEN, content, Map.of());
    }

    public void status(String content, Map<String, Object> data) {
        publish(StreamEventTypes.STATUS, content, data);
    }

    public void toolStart(String toolName, Map<String, Object> data) {
        publish(StreamEventTypes.TOOL_START, "调用工具：" + toolName, data);
    }

    public void toolEnd(String toolName, Map<String, Object> data) {
        publish(StreamEventTypes.TOOL_END, "工具完成：" + toolName, data);
    }

    public void card(String message, Map<String, Object> data) {
        publish(StreamEventTypes.CARD, message, data);
    }

    public void hitlRequired(String message, Map<String, Object> data) {
        publish(StreamEventTypes.HITL_REQUIRED, message, data);
    }

    private void publish(String type, String message, Map<String, Object> data) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("source", source);
        payload.put("invocationId", invocationId);
        if (data != null) {
            payload.putAll(data);
        }
        MarketingEventPublisher.publish(MarketingStreamEvent.of(conversationId, type, source, message, payload));
    }
}
