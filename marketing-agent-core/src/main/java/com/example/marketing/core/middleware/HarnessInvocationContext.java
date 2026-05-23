package com.example.marketing.core.middleware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.harness.HarnessTraceEvent;
import com.example.marketing.core.state.ConversationSession;

public class HarnessInvocationContext {
    private final String runId;
    private final MarketingRequest request;
    private final ConversationSession session;
    private final List<HarnessTraceEvent> trace;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public HarnessInvocationContext(String runId, MarketingRequest request, ConversationSession session,
                                    List<HarnessTraceEvent> trace) {
        this.runId = runId == null ? "" : runId;
        this.request = request;
        this.session = session;
        this.trace = trace == null ? new ArrayList<>() : trace;
    }

    public String runId() {
        return runId;
    }

    public MarketingRequest request() {
        return request;
    }

    public ConversationSession session() {
        return session;
    }

    public List<HarnessTraceEvent> trace() {
        return trace;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public void put(String key, Object value) {
        if (key != null && !key.isBlank()) {
            attributes.put(key, value);
        }
    }

    public Object get(String key) {
        return attributes.get(key);
    }
}
