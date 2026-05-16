package com.example.marketing.core.observability;

import java.util.Optional;

public final class AgentRuntimeContextHolder {
    private static final ThreadLocal<AgentRuntimeContext> CONTEXT = new ThreadLocal<>();

    private AgentRuntimeContextHolder() {
    }

    public static void withContext(AgentRuntimeContext context, Runnable runnable) {
        CONTEXT.set(context);
        try {
            runnable.run();
        }
        finally {
            CONTEXT.remove();
        }
    }

    public static Optional<AgentRuntimeContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }
}
