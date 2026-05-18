package com.example.marketing.core.agent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.audit.AuditEvent;
import com.example.marketing.core.audit.AuditEventPublisher;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.HumanFeedback;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.PendingActionStatus;
import com.example.marketing.core.model.PendingActionStateMachine;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.model.SubAgentResult;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.skill.SkillRegistry;
import com.example.marketing.core.state.ConversationSession;
import com.example.marketing.core.state.ConversationLockManager;
import com.example.marketing.core.state.ConversationStore;

@Service
public class AgentOrchestrator {
    private final ConversationStore conversationStore;
    private final MainAgent mainAgent;
    private final SubAgentRegistry subAgentRegistry;
    private final AuditEventPublisher auditEventPublisher;
    private final ConversationLockManager conversationLockManager;
    private final PendingActionStateMachine pendingActionStateMachine;
    private final SkillRegistry skillRegistry;

    public AgentOrchestrator(ConversationStore conversationStore, MainAgent mainAgent,
                             SubAgentRegistry subAgentRegistry, AuditEventPublisher auditEventPublisher,
                             ConversationLockManager conversationLockManager,
                             PendingActionStateMachine pendingActionStateMachine,
                             SkillRegistry skillRegistry) {
        this.conversationStore = conversationStore;
        this.mainAgent = mainAgent;
        this.subAgentRegistry = subAgentRegistry;
        this.auditEventPublisher = auditEventPublisher;
        this.conversationLockManager = conversationLockManager;
        this.pendingActionStateMachine = pendingActionStateMachine;
        this.skillRegistry = skillRegistry;
    }

    public MarketingResponse run(MarketingRequest request) {
        return conversationLockManager.withConversationLock(request.conversationId(), () -> runLocked(request));
    }

    private MarketingResponse runLocked(MarketingRequest request) {
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        String query = request.query() == null ? "" : request.query();
        Optional<HumanFeedback> feedback = HumanFeedback.fromVariables(request.variables());
        if (feedback.isPresent()) {
            auditEventPublisher.publish(AuditEvent.of("human_feedback_received", request.conversationId(),
                    "orchestrator", Map.of("decision", feedback.get().decision(),
                            "pendingActionId", feedback.get().pendingActionId(),
                            "visibleObjectId", feedback.get().visibleObjectId())));
            session.addMessage(ConversationMessage.user(query, Map.of("variables", request.variables())));
            MarketingResponse response = handleHumanFeedback(request, session, feedback.get());
            conversationStore.save(session);
            return response;
        }
        MainAgentDecision decision = mainAgent.decide(session, query, request.variables());
        auditEventPublisher.publish(AuditEvent.of("main_agent_decided", request.conversationId(), "main_agent",
                Map.of("action", decision.action(), "skillName", decision.skillName(),
                        "delegateTo", decision.delegateTo())));
        session.addMessage(ConversationMessage.user(query, Map.of("variables", request.variables())));
        if ("direct_reply".equals(decision.action()) || "ask_user".equals(decision.action())) {
            String answer = decision.reply().isBlank() ? "我需要更多信息才能继续处理。" : decision.reply();
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", decision.action(), Map.of()));
            MarketingResponse response = response(request, answer, session, decision, List.of());
            conversationStore.save(session);
            return response;
        }
        Optional<PendingAction> naturalResumeAction = Optional.empty();
        if ("resume_pending_action".equals(decision.action())) {
            naturalResumeAction = session.pendingActions().values().stream()
                    .filter(PendingAction::isPending)
                    .findFirst();
            naturalResumeAction.ifPresent(action -> {
                session.updatePendingAction(pendingActionStateMachine.approve(action, request.userId()));
                session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
            });
        }
        SubAgentResult result = delegate(request, session, decision);
        commit(session, result);
        if ("succeeded".equals(result.status())) {
            naturalResumeAction.ifPresent(action -> {
                PendingAction approved = session.pendingActions().get(action.id());
                session.updatePendingAction(pendingActionStateMachine.executed(approved, request.userId()));
                session.updateVisibleObjectStatus(action.visibleObjectId(), "executed");
            });
        }
        MarketingResponse response = response(request, result.userVisibleSummary(), session, decision,
                result.visibleObjects().stream().map(VisibleObject::title).toList());
        conversationStore.save(session);
        return response;
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
        validateSkillDelegation(decision.skillName(), decision.delegateTo());
        List<String> candidateSkills = candidateSkills(decision);
        SubAgentInvocation invocation = new SubAgentInvocation(invocationId, request.conversationId(),
                UUID.randomUUID().toString(), decision.skillName(), candidateSkills, decision.compressedContext(), inputs,
                decision.compressedContext(), List.copyOf(session.visibleObjects().values()),
                Map.of("visible_stream", true, "final_result_required", true, "may_request_hitl", true));
        SubAgent subAgent = subAgentRegistry.find(decision.delegateTo())
                .orElseThrow(() -> new IllegalStateException("Unknown sub agent: " + decision.delegateTo()));
        auditEventPublisher.publish(AuditEvent.of("subagent_started", request.conversationId(), subAgent.name(),
                Map.of("invocationId", invocation.invocationId(), "skillName", decision.skillName())));
        return subAgent.run(invocation, request);
    }

    private void validateSkillDelegation(String skillName, String delegateTo) {
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        String expectedEntryAgent = skillRegistry.find(skillName)
                .orElseThrow(() -> new IllegalStateException("Unknown skill: " + skillName))
                .entryAgent();
        if (!expectedEntryAgent.equals(delegateTo)) {
            throw new IllegalStateException("Skill " + skillName + " must delegate to "
                    + expectedEntryAgent + " but got " + delegateTo);
        }
    }

    private List<String> candidateSkills(MainAgentDecision decision) {
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        if (decision.skillName() != null && !decision.skillName().isBlank()) {
            skills.add(decision.skillName());
        }
        if (decision.candidateSkills() != null) {
            decision.candidateSkills().stream()
                    .filter(skill -> skill != null && !skill.isBlank())
                    .forEach(skills::add);
        }
        skills.forEach(skill -> skillRegistry.find(skill)
                .orElseThrow(() -> new IllegalStateException("Unknown candidate skill: " + skill)));
        return List.copyOf(skills);
    }

    private List<String> candidateSkills(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return List.of();
        }
        skillRegistry.find(skillName).orElseThrow(() -> new IllegalStateException("Unknown skill: " + skillName));
        return List.of(skillName);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
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
            PendingAction expired = pendingActionStateMachine.expire(action, request.userId());
            session.updatePendingAction(expired);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "expired");
            String answer = "这个确认操作已经过期，请重新发起。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_expired",
                    Map.of("pendingActionId", action.id())));
            return response(request, answer, session, null, List.of());
        }
        if (feedback.isReject()) {
            auditEventPublisher.publish(AuditEvent.of("hitl_rejected", request.conversationId(), "orchestrator",
                    Map.of("pendingActionId", action.id())));
            PendingAction rejected = pendingActionStateMachine.reject(action, request.userId());
            session.updatePendingAction(rejected);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "rejected");
            String answer = "已取消这次待确认操作。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_rejected",
                    Map.of("pendingActionId", action.id())));
            return response(request, answer, session, null, List.of());
        }
        if (feedback.isEdit()) {
            auditEventPublisher.publish(AuditEvent.of("hitl_edited", request.conversationId(), "orchestrator",
                    Map.of("pendingActionId", action.id())));
            PendingAction edited = pendingActionStateMachine.edit(action, feedback.editedPayload(), request.userId());
            session.updatePendingAction(edited);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "edited");
            String answer = "已记录你的调整，请重新确认后再执行。";
            session.addMessage(ConversationMessage.assistant(answer, "main_agent", "hitl_edited",
                    Map.of("pendingActionId", action.id())));
            return response(request, answer, session, null, List.of());
        }
        PendingAction approved = pendingActionStateMachine.approve(action, request.userId());
        auditEventPublisher.publish(AuditEvent.of("hitl_approved", request.conversationId(), "orchestrator",
                Map.of("pendingActionId", action.id(), "sourceAgent", action.sourceAgent())));
        session.updatePendingAction(approved);
        session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
        Map<String, Object> inputs = new LinkedHashMap<>(action.payload());
        inputs.putAll(feedback.editedPayload());
        inputs.put("confirmed", true);
        inputs.put("human_feedback_decision", feedback.decision());
        String skillName = stringValue(inputs.get("skill_name"));
        SubAgentInvocation invocation = new SubAgentInvocation("resume_" + UUID.randomUUID().toString().substring(0, 8),
                request.conversationId(), UUID.randomUUID().toString(), skillName, candidateSkills(skillName),
                "resume pending action", inputs,
                "用户已确认待执行动作", List.copyOf(session.visibleObjects().values()),
                Map.of("visible_stream", true, "final_result_required", true, "human_feedback", true));
        SubAgent subAgent = subAgentRegistry.find(action.sourceAgent())
                .orElseThrow(() -> new IllegalStateException("Unknown sub agent: " + action.sourceAgent()));
        SubAgentResult result = subAgent.run(invocation, request);
        commit(session, result);
        if ("succeeded".equals(result.status())) {
            session.updatePendingAction(pendingActionStateMachine.executed(approved, request.userId()));
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
