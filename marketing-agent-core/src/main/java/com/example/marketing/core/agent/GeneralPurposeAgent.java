package com.example.marketing.core.agent;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;

@Service
public class GeneralPurposeAgent implements SubAgent {
    @Override
    public String name() {
        return "general_purpose_agent";
    }

    @Override
    public SubAgentWorkers workers() {
        return SubAgentWorkers.stateless(java.util.Set.of("task", "expectedOutput"));
    }

    @Override
    public SubAgentProfile profile() {
        return new SubAgentProfile(name(),
                "Read scoped context refs and produce a concise isolated analysis summary. No side effects.",
                "/subagents/general_purpose_agent.md",
                List.of("read_workspace", "search_workspace", "search_knowledge_base", "read_skill"),
                List.of("workspace.read", "knowledge.retrieve"),
                List.of("rule_inquiry", "spreadsheet_summarize", "spreadsheet_query_product",
                        "activity_rule_check", "copywriting"),
                6,
                4_000,
                Map.of("type", "observation", "required", List.of("summary", "confidence", "workspace_refs")));
    }

    @Override
    public SubAgentResult run(SubAgentInvocation invocation, MarketingRequest request) {
        String expected = stringValue(invocation.inputs().get("expectedOutput"));
        String refs = stringValue(invocation.inputs().get("contextRefs"));
        String summary = "Delegated task received in isolated context: " + invocation.task()
                + (expected.isBlank() ? "" : ". Expected output: " + expected)
                + (refs.isBlank() ? "" : ". Context refs: " + refs);
        return new SubAgentResult(invocation.invocationId(), "succeeded", summary,
                summary, Map.of("subagent", name(), "delegated_task", invocation.task()),
                List.of(), List.of(ConversationMessage.assistant(summary, name(), "delegated_summary",
                Map.of("invocationId", invocation.invocationId()))), Map.of());
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}

