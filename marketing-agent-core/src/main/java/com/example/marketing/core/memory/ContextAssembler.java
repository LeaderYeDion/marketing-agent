package com.example.marketing.core.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.agent.SubAgentProfile;
import com.example.marketing.core.agent.SubAgentProfileRegistry;
import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.state.ConversationSession;
import com.example.marketing.core.workspace.AgentWorkspace;
import com.example.marketing.core.workspace.WorkspaceDocument;
import com.example.marketing.core.workspace.WorkspaceEntry;

@Service
public class ContextAssembler {
    private static final int RECENT_VISIBLE_MESSAGE_LIMIT = 12;
    private final AgentWorkspace workspace;
    private final SubAgentProfileRegistry subAgentProfileRegistry;

    public ContextAssembler(AgentWorkspace workspace, SubAgentProfileRegistry subAgentProfileRegistry) {
        this.workspace = workspace;
        this.subAgentProfileRegistry = subAgentProfileRegistry;
    }

    public HarnessContext assemble(MarketingRequest request, ConversationSession session,
                                   List<CapabilityDescriptor> capabilities) {
        Map<String, Object> workspaceRefs = refreshConversationHistory(request, session);
        List<WorkspaceEntry> workspaceEntries = workspace.list(request.conversationId(), "/");
        HarnessMemory memory = new HarnessMemory(
                recentVisibleMessages(session),
                lastItems(session.handoffSummaries(), 8),
                session.visibleObjects(),
                session.pendingActions(),
                session.state(),
                mapValue(session.state().get("task_memory")),
                mapValue(session.state().get("artifact_memory")),
                mapValue(session.state().get("decision_memory")),
                workspaceRefs,
                workspaceEntries
        );
        List<SubAgentProfile> subAgentProfiles = subAgentProfileRegistry.list();
        return new HarnessContext(MarketingAgentContext.from(request), memory, capabilities, subAgentProfiles,
                compressedContext(request, memory, capabilities, subAgentProfiles));
    }

    private Map<String, Object> refreshConversationHistory(MarketingRequest request, ConversationSession session) {
        List<ConversationMessage> visibleMessages = session.messages().stream()
                .filter(ConversationMessage::visible)
                .toList();
        int archiveCount = Math.max(0, visibleMessages.size() - RECENT_VISIBLE_MESSAGE_LIMIT);
        Map<String, Object> refs = new LinkedHashMap<>(mapValue(session.state().get("workspace_refs")));
        if (archiveCount == 0) {
            return refs;
        }
        String path = "/conversation_history/" + request.conversationId() + ".md";
        String content = conversationHistoryMarkdown(visibleMessages.subList(0, archiveCount));
        WorkspaceDocument document = workspace.write(request.conversationId(), path, content, Map.of(
                "type", "conversation_history",
                "conversation_id", request.conversationId(),
                "archived_message_count", archiveCount
        ));
        refs.put("conversation_history", document.path());
        refs.put("conversation_history_archived_message_count", archiveCount);
        session.state().put("workspace_refs", refs);
        if (session.handoffSummaries().stream().noneMatch(summary -> document.path()
                .equals(String.valueOf(summary.metadata().get("workspace_path"))))) {
            session.addHandoffSummary(ContextSummary.of("workspace", "conversation_history", "archived",
                    "Older visible conversation messages were archived to workspace.",
                    Map.of("workspace_path", document.path(), "archived_message_count", archiveCount)));
        }
        return refs;
    }

    private String conversationHistoryMarkdown(List<ConversationMessage> messages) {
        StringBuilder builder = new StringBuilder("# Archived conversation history\n\n");
        for (ConversationMessage message : messages) {
            builder.append("## ").append(message.createdAt()).append(" ")
                    .append(message.role()).append(" [").append(message.source()).append("/")
                    .append(message.eventType()).append("]\n\n")
                    .append(message.content() == null ? "" : message.content())
                    .append("\n\n");
        }
        return builder.toString();
    }

    private String compressedContext(MarketingRequest request, HarnessMemory memory,
                                     List<CapabilityDescriptor> capabilities,
                                     List<SubAgentProfile> subAgentProfiles) {
        StringBuilder builder = new StringBuilder();
        builder.append("User goal: ").append(request.query() == null ? "" : request.query()).append("\n");
        builder.append("Capabilities:\n");
        for (CapabilityDescriptor capability : capabilities) {
            builder.append("- ").append(capability.name())
                    .append(" description=").append(capability.description())
                    .append(" required=").append(capability.requiredInputs())
                    .append(" executionMode=").append(capability.executionMode())
                    .append(" type=").append(capability.capabilityType())
                    .append(" inputSchema=").append(capability.inputSchema())
                    .append(" outputSchema=").append(capability.outputSchema())
                    .append(" risk=").append(capability.riskLevel())
                    .append(" provider=").append(capability.provider())
                    .append("\n");
        }
        builder.append("Delegation agents for delegate_task:\n");
        for (SubAgentProfile profile : subAgentProfiles) {
            builder.append("- ").append(profile.name())
                    .append(": ").append(profile.description())
                    .append(" tools=").append(profile.allowedTools())
                    .append(" permissions=").append(profile.permissionProfile())
                    .append(" maxSteps=").append(profile.maxSteps())
                    .append("\n");
        }
        builder.append("Visible objects: ").append(memory.visibleObjects().keySet()).append("\n");
        builder.append("Pending actions: ").append(memory.pendingActions().keySet()).append("\n");
        builder.append("Working state keys: ").append(memory.workingState().keySet()).append("\n");
        builder.append("Workspace refs: ").append(memory.workspaceRefs()).append("\n");
        builder.append("Workspace root entries: ")
                .append(memory.workspaceEntries().stream().map(WorkspaceEntry::path).toList()).append("\n");
        builder.append("Use workspace refs for recoverable history, observations, artifacts, and evidence instead ")
                .append("of assuming truncated prompt context is complete.\n");
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
        List<ConversationMessage> visibleMessages = session.messages().stream()
                .filter(com.example.marketing.core.model.ConversationMessage::visible)
                .toList();
        return visibleMessages.stream()
                .skip(Math.max(0, visibleMessages.size() - RECENT_VISIBLE_MESSAGE_LIMIT))
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
