package com.example.marketing.core.middleware;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.marketing.core.harness.HarnessTraceEvent;
import com.example.marketing.core.memory.HarnessContext;

@Component
public class ContextBudgetMiddleware implements HarnessMiddleware {
    private static final int DEFAULT_CONTEXT_CHAR_BUDGET = 12_000;

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void afterContextAssemble(HarnessInvocationContext invocation, HarnessContext context) {
        int size = context.compressedContext().length();
        Map<String, Object> budget = Map.of(
                "compressedContextChars", size,
                "budgetChars", DEFAULT_CONTEXT_CHAR_BUDGET,
                "overflow", size > DEFAULT_CONTEXT_CHAR_BUDGET
        );
        invocation.put("context_budget", budget);
        if (size > DEFAULT_CONTEXT_CHAR_BUDGET) {
            invocation.trace().add(HarnessTraceEvent.of(invocation.runId(), "context_overflow",
                    "ContextBudgetMiddleware", "overflow", budget));
            onContextOverflow(invocation, context);
        }
    }
}
