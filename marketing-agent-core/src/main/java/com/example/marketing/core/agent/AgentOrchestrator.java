package com.example.marketing.core.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.context.MarketingAgentContext;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.state.ConversationSession;
import com.example.marketing.core.state.ConversationStore;

@Service
public class AgentOrchestrator {
    private final ConversationStore conversationStore;
    private final MainAgent mainAgent;
    private final ActivityEnrollAgent activityEnrollAgent;
    private final InquiryAgent inquiryAgent;

    public AgentOrchestrator(ConversationStore conversationStore, MainAgent mainAgent,
                             ActivityEnrollAgent activityEnrollAgent, InquiryAgent inquiryAgent) {
        this.conversationStore = conversationStore;
        this.mainAgent = mainAgent;
        this.activityEnrollAgent = activityEnrollAgent;
        this.inquiryAgent = inquiryAgent;
    }

    public MarketingResponse run(MarketingRequest request) {
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        String query = request.query() == null ? "" : request.query();
        session.addMessage(ConversationMessage.user(query, Map.of("variables", request.variables())));
        MainAgentDecision decision = mainAgent.decide(session, query, request.variables());
        if ("direct_reply".equals(decision.action()) || "ask_user".equals(decision.action())) {
            String answer = decision.reply().isBlank() ? "我需要更多信息才能继续处理。" : decision.reply();
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", decision.action(), Map.of()));
            return response(request, answer, session, decision, List.of());
        }
        SubAgentResult result = delegate(request, session, decision);
        commit(session, result);
        return response(request, result.userVisibleSummary(), session, decision,
                result.visibleObjects().stream().map(VisibleObject::title).toList());
    }

    private SubAgentResult delegate(MarketingRequest request, ConversationSession session, MainAgentDecision decision) {
        String invocationId = "subrun_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> inputs = new LinkedHashMap<>(decision.inputs());
        if ("resume_pending_action".equals(decision.action())) {
            inputs.put("confirmed", true);
            session.pendingActions().values().stream().findFirst().ifPresent(action -> inputs.putAll(action));
        }
        SubAgentInvocation invocation = new SubAgentInvocation(invocationId, request.conversationId(),
                UUID.randomUUID().toString(), decision.skillName(), decision.compressedContext(), inputs,
                decision.compressedContext(), List.copyOf(session.visibleObjects().values()),
                Map.of("visible_stream", true, "final_result_required", true, "may_request_hitl", true));
        if ("activity_enroll_agent".equals(decision.delegateTo())) {
            if (!inputs.containsKey("excel_file_path") || !inputs.containsKey("activity_id")) {
                String message = "要报名优惠，我还需要本地 Excel 文件路径和活动 ID。";
                return SubAgentResult.failed(invocationId, message, message, Map.of("error_code", "MISSING_INPUT"),
                        List.of(ConversationMessage.assistant(message, "main_agent", "ask_user", Map.of())));
            }
            return activityEnrollAgent.run(invocation);
        }
        return inquiryAgent.run(invocation, MarketingAgentContext.from(request));
    }

    private void commit(ConversationSession session, SubAgentResult result) {
        result.messagesToCommit().forEach(session::addMessage);
        result.visibleObjects().forEach(object -> {
            session.addVisibleObject(object);
            if ("hitl_confirmation".equals(object.type()) && "pending".equals(object.status())) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.putAll(object.data());
                pending.put("visible_object_id", object.id());
                session.addPendingAction(object.id(), pending);
            }
        });
        if (result.statePatch() != null) {
            session.state().putAll(result.statePatch());
        }
        if ("succeeded".equals(result.status())) {
            session.pendingActions().clear();
        }
    }

    private MarketingResponse response(MarketingRequest request, String answer, ConversationSession session,
                                       MainAgentDecision decision, List<String> visibleObjectTitles) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", decision);
        metadata.put("state", session.state());
        metadata.put("visibleObjects", session.visibleObjects().keySet());
        metadata.put("pendingActions", session.pendingActions().keySet());
        return new MarketingResponse(request.conversationId(), answer == null ? "" : answer, List.of(),
                visibleObjectTitles, metadata);
    }
}
