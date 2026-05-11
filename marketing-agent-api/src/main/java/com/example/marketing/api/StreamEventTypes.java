package com.example.marketing.api;

public final class StreamEventTypes {
    public static final String START = "start";
    public static final String NODE_START = "node_start";
    public static final String NODE_END = "node_end";
    public static final String TOKEN = "token";
    public static final String STATUS = "status";
    public static final String CARD = "card";
    public static final String TOOL_START = "tool_start";
    public static final String TOOL_END = "tool_end";
    public static final String HITL_REQUIRED = "hitl_required";
    public static final String STATE_PATCH = "state_patch";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    private StreamEventTypes() {
    }
}
