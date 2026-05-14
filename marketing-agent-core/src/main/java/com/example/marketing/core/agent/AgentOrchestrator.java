package com.example.marketing.core.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.HumanFeedback;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.PendingActionStatus;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.state.ConversationSession;
import com.example.marketing.core.state.ConversationStore;

@Service
public class AgentOrchestrator {
    private final ConversationStore conversationStore;
    private final MainAgent mainAgent;
    private final SubAgentRegistry subAgentRegistry;

    public AgentOrchestrator(ConversationStore conversationStore, MainAgent mainAgent,
                             SubAgentRegistry subAgentRegistry) {
        this.conversationStore = conversationStore;
        this.mainAgent = mainAgent;
        this.subAgentRegistry = subAgentRegistry;
    }

    public MarketingResponse run(MarketingRequest request) {
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        String query = request.query() == null ? "" : request.query();
        Optional<HumanFeedback> feedback = HumanFeedback.fromVariables(request.variables());
        if (feedback.isPresent()) {
            session.addMessage(ConversationMessage.user(query, Map.of("variables", request.variables())));
            return handleHumanFeedback(request, session, feedback.get());
        }
        MainAgentDecision decision = mainAgent.decide(session, query, request.variables());
        session.addMessage(ConversationMessage.user(query, Map.of("variables", request.variables())));
        if ("direct_reply".equals(decision.action()) || "ask_user".equals(decision.action())) {
            String answer = decision.reply().isBlank() ? "我需要更多信息才能继续处理。" : decision.reply();
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", decision.action(), Map.of()));
            return response(request, answer, session, decision, List.of());
        }
        Optional<PendingAction> naturalResumeAction = Optional.empty();
        if ("resume_pending_action".equals(decision.action())) {
            naturalResumeAction = session.pendingActions().values().stream()
                    .filter(PendingAction::isPending)
                    .findFirst();
            naturalResumeAction.ifPresent(action -> {
                session.updatePendingAction(action.withStatus(PendingActionStatus.APPROVED, request.userId()));
                session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
            });
        }
        SubAgentResult result = delegate(request, session, decision);
        commit(session, result);
        if ("succeeded".equals(result.status())) {
            naturalResumeAction.ifPresent(action -> {
                session.updatePendingAction(action.withStatus(PendingActionStatus.EXECUTED, request.userId()));
                session.updateVisibleObjectStatus(action.visibleObjectId(), "executed");
            });
        }
        return response(request, result.userVisibleSummary(), session, decision,
                result.visibleObjects().stream().map(VisibleObject::title).toList());
    }

    private SubAgentResult delegate(MarketingRequest request, ConversationSession session, MainAgentDecision decision) {
        String invocationId = "subrun_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> inputs = new LinkedHashMap<>(decision.inputs());
        if ("resume_pending_action".equals(decision.action())) {
            inputs.put("confirmed", true);
            session.pendingActions().values().stream()
                    .filter(action -> action.isPending() || PendingActionStatus.APPROVED.equals(action.status()))
                    .findFirst()
                    .ifPresent(action -> inputs.putAll(action.payload()));
        }
        SubAgentInvocation invocation = new SubAgentInvocation(invocationId, request.conversationId(),
                UUID.randomUUID().toString(), decision.skillName(), decision.compressedContext(), inputs,
                decision.compressedContext(), List.copyOf(session.visibleObjects().values()),
                Map.of("visible_stream", true, "final_result_required", true, "may_request_hitl", true));
        SubAgent subAgent = subAgentRegistry.find(decision.delegateTo())
                .orElseThrow(() -> new IllegalStateException("Unknown sub agent: " + decision.delegateTo()));
        return subAgent.run(invocation, request);
    }

    private MarketingResponse handleHumanFeedback(MarketingRequest request, ConversationSession session,
                                                  HumanFeedback feedback) {
        PendingAction action = session.findPendingAction(feedback.pendingActionId(), feedback.visibleObjectId())
                .orElse(null);
        if (action == null) {
            String answer = "没有找到对应的待确认操作，请重新发起。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_missing", Map.of()));
            return response(request, answer, session, null, List.of());
        }
        if (!action.isPending()) {
            String answer = "这个操作当前状态是 " + action.status() + "，不能重复处理。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_not_pending",
                    Map.of("pendingActionId", action.id(), "status", action.status().name())));
            return response(request, answer, session, null, List.of());
        }
        if (action.isExpired()) {
            PendingAction expired = action.withStatus(PendingActionStatus.EXPIRED, request.userId());
            session.updatePendingAction(expired);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "expired");
            String answer = "这个确认操作已经过期，请重新发起。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_expired",
                    Map.of("pendingActionId", action.id())));
            return response(request, answer, session, null, List.of());
        }
        if (feedback.isReject()) {
            PendingAction rejected = action.withStatus(PendingActionStatus.REJECTED, request.userId());
            session.updatePendingAction(rejected);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "rejected");
            String answer = "已取消这次待确认操作。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_rejected",
                    Map.of("pendingActionId", action.id())));
            return response(request, answer, session, null, List.of());
        }
        if (feedback.isEdit()) {
            PendingAction edited = action.withEditedPayload(feedback.editedPayload(), request.userId());
            session.updatePendingAction(edited);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "edited");
            String answer = "已记录你的调整，请重新确认后再执行。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_edited",
                    Map.of("pendingActionId", action.id())));
            return response(request, answer, session, null, List.of());
        }
        PendingAction approved = action.withStatus(PendingActionStatus.APPROVED, request.userId());
        session.updatePendingAction(approved);
        session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
        Map<String, Object> inputs = new LinkedHashMap<>(action.payload());
        inputs.putAll(feedback.editedPayload());
        inputs.put("confirmed", true);
        inputs.put("human_feedback_decision", feedback.decision());
        SubAgentInvocation invocation = new SubAgentInvocation("resume_" + UUID.randomUUID().toString().substring(0, 8),
                request.conversationId(), UUID.randomUUID().toString(), "", "resume pending action", inputs,
                "用户已确认待执行动作", List.copyOf(session.visibleObjects().values()),
                Map.of("visible_stream", true, "final_result_required", true, "human_feedback", true));
        SubAgent subAgent = subAgentRegistry.find(action.sourceAgent())
                .orElseThrow(() -> new IllegalStateException("Unknown sub agent: " + action.sourceAgent()));
        SubAgentResult result = subAgent.run(invocation, request);
        commit(session, result);
        if ("succeeded".equals(result.status())) {
            session.updatePendingAction(approved.withStatus(PendingActionStatus.EXECUTED, request.userId()));
            session.updateVisibleObjectStatus(action.visibleObjectId(), "executed");
        }
        return response(request, result.userVisibleSummary(), session, null,
                result.visibleObjects().stream().map(VisibleObject::title).toList());
    }

    private void commit(ConversationSession session, SubAgentResult result) {
        if (result.mainContextSummary() != null && !result.mainContextSummary().isBlank()) {
            session.addHandoffSummary(ContextSummary.of("sub_agent", result.invocationId(), result.status(),
                    result.mainContextSummary(), Map.of()));
        }
        result.messagesToCommit().forEach(session::addMessage);
        result.visibleObjects().forEach(object -> {
            session.addVisibleObject(object);
            if ("hitl_confirmation".equals(object.type()) && "pending".equals(object.status())) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.putAll(object.data());
                pending.put("visible_object_id", object.id());
                String sourceAgent = String.valueOf(pending.getOrDefault("source_agent", "activity_enroll_agent"));
                session.addPendingAction(PendingAction.create(object.id(), object.type(), sourceAgent,
                        result.invocationId(), object.id(), pending));
            }
        });
        if (result.statePatch() != null) {
            session.state().putAll(result.statePatch());
        }
    }

    private MarketingResponse response(MarketingRequest request, String answer, ConversationSession session,
                                       MainAgentDecision decision, List<String> visibleObjectTitles) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", decision);
        metadata.put("state", session.state());
        metadata.put("visibleObjects", session.visibleObjects().keySet());
        metadata.put("pendingActions", session.activePendingActionIds());
        return new MarketingResponse(request.conversationId(), answer == null ? "" : answer, List.of(),
                visibleObjectTitles, metadata);
    }
}
