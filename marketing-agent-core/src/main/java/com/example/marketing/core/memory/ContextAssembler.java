package com.example.marketing.core.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.state.ConversationSession;

@Service
public class ContextAssembler {
    public HarnessContext assemble(MarketingRequest request, ConversationSession session,
                                   List<CapabilityDescriptor> capabilities) {
        HarnessMemory memory = new HarnessMemory(
                recentVisibleMessages(session),
                lastItems(session.handoffSummaries(), 8),
                session.visibleObjects(),
                session.pendingActions(),
                session.state(),
                mapValue(session.state().get("task_memory")),
                mapValue(session.state().get("artifact_memory")),
                mapValue(session.state().get("decision_memory"))
        );
        return new HarnessContext(MarketingAgentContext.from(request), memory, capabilities,
                compressedContext(request, memory, capabilities));
    }

    private String compressedContext(MarketingRequest request, HarnessMemory memory,
                                     List<CapabilityDescriptor> capabilities) {
        StringBuilder builder = new StringBuilder();
        builder.append("User goal: ").append(request.query() == null ? "" : request.query()).append("\n");
        builder.append("Capabilities:\n");
        for (CapabilityDescriptor capability : capabilities) {
            builder.append("- ").append(capability.name())
                    .append(" required=").append(capability.requiredInputs())
                    .append(" type=").append(capability.capabilityType())
                    .append(" inputSchema=").append(capability.inputSchema())
                    .append(" outputSchema=").append(capability.outputSchema())
                    .append(" risk=").append(capability.riskLevel())
                    .append(" provider=").append(capability.provider())
                    .append("\n");
        }
        builder.append("Visible objects: ").append(memory.visibleObjects().keySet()).append("\n");
        builder.append("Pending actions: ").append(memory.pendingActions().keySet()).append("\n");
        builder.append("Working state keys: ").append(memory.workingState().keySet()).append("\n");
        return builder.toString();
    }

    private <T> List<T> lastItems(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().skip(Math.max(0, items.size() - limit)).toList();
    }

    private List<com.example.marketing.core.model.ConversationMessage> recentVisibleMessages(
            ConversationSession session) {
        return session.messages().stream()
                .filter(com.example.marketing.core.model.ConversationMessage::visible)
                .skip(Math.max(0, session.messages().size() - 12))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, item) -> typed.put(String.valueOf(key), item));
            return typed;
        }
        return Map.of();
    }
}
