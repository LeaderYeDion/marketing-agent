package com.example.marketing.core.harness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.audit.AuditEvent;
import com.example.marketing.core.audit.AuditEventPublisher;
import com.example.marketing.core.capability.CapabilityDescriptor;
import com.example.marketing.core.capability.CapabilityExecutionRequest;
import com.example.marketing.core.capability.CapabilityProvider;
import com.example.marketing.core.capability.CapabilityRegistry;
import com.example.marketing.core.memory.ContextAssembler;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.HumanFeedback;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.PendingActionStateMachine;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.observability.AgentTelemetry;
import com.example.marketing.core.policy.RiskAssessment;
import com.example.marketing.core.policy.RiskPolicyEngine;
import com.example.marketing.core.recovery.RecoveryDecision;
import com.example.marketing.core.recovery.RecoveryPolicyEngine;
import com.example.marketing.core.state.ConversationLockManager;
import com.example.marketing.core.state.ConversationSession;
import com.example.marketing.core.state.ConversationStore;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;
import com.example.marketing.core.task.TaskNodeStatus;

@Service
public class MarketingHarness {
    private final ConversationStore conversationStore;
    private final ConversationLockManager conversationLockManager;
    private final CapabilityRegistry capabilityRegistry;
    private final List<CapabilityProvider> capabilityProviders;
    private final ContextAssembler contextAssembler;
    private final TaskPlanner taskPlanner;
    private final RiskPolicyEngine riskPolicyEngine;
    private final RecoveryPolicyEngine recoveryPolicyEngine;
    private final AuditEventPublisher auditEventPublisher;
    private final AgentTelemetry telemetry;
    private final PendingActionStateMachine pendingActionStateMachine;

    public MarketingHarness(ConversationStore conversationStore,
                            ConversationLockManager conversationLockManager,
                            CapabilityRegistry capabilityRegistry,
                            List<CapabilityProvider> capabilityProviders,
                            ContextAssembler contextAssembler,
                            TaskPlanner taskPlanner,
                            RiskPolicyEngine riskPolicyEngine,
                            RecoveryPolicyEngine recoveryPolicyEngine,
                            AuditEventPublisher auditEventPublisher,
                            AgentTelemetry telemetry,
                            PendingActionStateMachine pendingActionStateMachine) {
        this.conversationStore = conversationStore;
        this.conversationLockManager = conversationLockManager;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityProviders = capabilityProviders == null ? List.of() : List.copyOf(capabilityProviders);
        this.contextAssembler = contextAssembler;
        this.taskPlanner = taskPlanner;
        this.riskPolicyEngine = riskPolicyEngine;
        this.recoveryPolicyEngine = recoveryPolicyEngine;
        this.auditEventPublisher = auditEventPublisher;
        this.telemetry = telemetry;
        this.pendingActionStateMachine = pendingActionStateMachine;
    }

    public MarketingResponse run(MarketingRequest request) {
        java.util.Optional<HumanFeedback> feedback = HumanFeedback.fromVariables(request.variables());
        if (feedback.isPresent()) {
            return conversationLockManager.withConversationLock(request.conversationId(),
                    () -> runFeedbackLocked(request, feedback.get()));
        }
        return conversationLockManager.withConversationLock(request.conversationId(), () -> runLocked(request));
    }

    private MarketingResponse runFeedbackLocked(MarketingRequest request, HumanFeedback feedback) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        session.addMessage(ConversationMessage.user(request.query() == null ? "" : request.query(),
                Map.of("variables", request.variables() == null ? Map.of() : request.variables())));
        List<HarnessTraceEvent> trace = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        List<RiskAssessment> riskAssessments = new ArrayList<>();
        List<CapabilityDescriptor> capabilities = capabilityRegistry.list();
        trace(trace, runId, "human_feedback_received", "harness", feedback.decision(),
                Map.of("pendingActionId", feedback.pendingActionId(), "visibleObjectId",
                        feedback.visibleObjectId()));
        auditEventPublisher.publish(AuditEvent.of("human_feedback_received", request.conversationId(), "harness",
                Map.of("decision", feedback.decision(), "pendingActionId", feedback.pendingActionId(),
                        "visibleObjectId", feedback.visibleObjectId())));

        PendingAction action = session.findPendingAction(feedback.pendingActionId(), feedback.visibleObjectId())
                .orElse(null);
        if (action == null) {
            return finishFeedback(request, session, runId, "feedback_missing",
                    "没有找到对应的待确认动作，请重新发起。", observations, riskAssessments, trace, capabilities, null);
        }
        if (!action.isPending()) {
            return finishFeedback(request, session, runId, "feedback_not_pending",
                    "这个动作当前状态是 " + action.status() + "，不能重复处理。", observations, riskAssessments,
                    trace, capabilities, action);
        }
        if (action.isExpired()) {
            PendingAction expired = pendingActionStateMachine.expire(action, request.userId());
            session.updatePendingAction(expired);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "expired");
            return finishFeedback(request, session, runId, "feedback_expired",
                    "这个确认动作已经过期，请重新发起。", observations, riskAssessments, trace, capabilities, expired);
        }
        if (feedback.isReject()) {
            PendingAction rejected = pendingActionStateMachine.reject(action, request.userId());
            session.updatePendingAction(rejected);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "rejected");
            auditEventPublisher.publish(AuditEvent.of("hitl_rejected", request.conversationId(), "harness",
                    Map.of("pendingActionId", action.id())));
            return finishFeedback(request, session, runId, "feedback_rejected",
                    "已取消这次待确认动作。", observations, riskAssessments, trace, capabilities, rejected);
        }
        if (feedback.isEdit()) {
            PendingAction edited = pendingActionStateMachine.edit(action, feedback.editedPayload(), request.userId());
            session.updatePendingAction(edited);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "edited");
            auditEventPublisher.publish(AuditEvent.of("hitl_edited", request.conversationId(), "harness",
                    Map.of("pendingActionId", action.id())));
            return finishFeedback(request, session, runId, "feedback_edited",
                    "已记录你的调整，请重新确认后再执行。", observations, riskAssessments, trace, capabilities, edited);
        }

        PendingAction approved = pendingActionStateMachine.approve(action, request.userId());
        session.updatePendingAction(approved);
        session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
        auditEventPublisher.publish(AuditEvent.of("hitl_approved", request.conversationId(), "harness",
                Map.of("pendingActionId", action.id(), "sourceAgent", action.sourceAgent())));

        CapabilityDescriptor capability = capabilityForPendingAction(approved, capabilities);
        if (capability == null) {
            return finishFeedback(request, session, runId, "feedback_capability_missing",
                    "没有找到可以继续执行这个确认动作的能力。", observations, riskAssessments, trace, capabilities,
                    approved);
        }
        Map<String, Object> inputs = feedbackInputs(approved, feedback);
        TaskGraph graph = graphForFeedback(session, approved);
        TaskNode node = nodeForFeedback(graph, approved, capability, inputs).withStatus(TaskNodeStatus.RUNNING);
        RiskAssessment risk = riskPolicyEngine.assess(capability, node, inputs);
        riskAssessments.add(risk);
        node = node.withRisk(risk.riskLevel());
        graph = graph == null
                ? new TaskGraph("tg_feedback_" + UUID.randomUUID().toString().substring(0, 8),
                "Resume pending action " + approved.id(), List.of(node), "running")
                : graph.withNode(node).withStatus("running");
        HarnessContext context = contextAssembler.assemble(request, session, capabilities);
        Observation observation = executeCapability(runId, graph, node, capability, context, request, session);
        observations.add(observation);
        commitObservation(session, observation, graph);
        TaskNodeStatus status = statusFor(observation);
        TaskNode completedNode = node.withObservation(status, observation.id());
        graph = graph.withNode(completedNode);
        trace(trace, runId, "feedback_capability_executed", capability.name(), observation.status(),
                Map.of("pendingActionId", approved.id(), "observationId", observation.id()));
        if (TaskNodeStatus.SUCCEEDED.equals(status)) {
            PendingAction current = session.pendingActions().getOrDefault(approved.id(), approved);
            session.updatePendingAction(pendingActionStateMachine.executed(current, request.userId()));
            session.updateVisibleObjectStatus(approved.visibleObjectId(), "executed");
        }
        RecoveryDecision recovery = recoveryPolicyEngine.decide(capability, observation);
        trace(trace, runId, "feedback_recovery_evaluated", capability.name(), recovery.action(),
                Map.of("reason", recovery.reason(), "fallback", recovery.fallbackCapability()));
        graph = applyRecoveryIfNeeded(graph, completedNode, capability, observation, recovery, observations, trace,
                runId);
        if (graph.hasPendingNodes() && !graph.isPausedOrTerminal()) {
            HarnessContext resumedContext = contextAssembler.assemble(request, session, capabilities);
            graph = executePlannedGraph(runId, request, session, resumedContext, graph, observations,
                    riskAssessments, trace);
        }
        session.state().put("last_task_graph", graph);
        session.state().put("decision_memory", Map.of(
                "last_run_id", runId,
                "last_observation_count", observations.size(),
                "last_status", graph.status(),
                "last_feedback_action_id", approved.id()
        ));
        conversationStore.save(session);
        return new MarketingResponse(request.conversationId(), answerFor(graph, observations), List.of(),
                observations.stream()
                        .flatMap(item -> item.visibleObjects().stream())
                        .map(VisibleObject::title)
                        .toList(),
                metadata(request, session, graph, observations, riskAssessments, trace, capabilities));
    }

    private MarketingResponse runLocked(MarketingRequest request) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        session.addMessage(ConversationMessage.user(request.query() == null ? "" : request.query(),
                Map.of("variables", request.variables() == null ? Map.of() : request.variables())));

        List<CapabilityDescriptor> capabilities = capabilityRegistry.list();
        HarnessContext context = contextAssembler.assemble(request, session, capabilities);
        List<HarnessTraceEvent> trace = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        List<RiskAssessment> riskAssessments = new ArrayList<>();

        trace(trace, runId, "context_assembled", "harness", "succeeded",
                Map.of("capabilityCount", capabilities.size(), "visibleObjects",
                        context.memory().visibleObjects().size()));

        TaskGraph graph = taskPlanner.plan(request, context);
        trace(trace, runId, "task_graph_planned", "planner", graph.status(),
                Map.of("taskGraphId", graph.id(), "nodeCount", graph.nodes().size()));
        telemetry.event("task_graph_planned", "harness", graph.status(),
                Map.of("taskGraphId", graph.id(), "nodeCount", graph.nodes().size()));
        auditEventPublisher.publish(AuditEvent.of("task_graph_planned", request.conversationId(), "harness",
                Map.of("runId", runId, "taskGraphId", graph.id(), "nodeCount", graph.nodes().size())));

        graph = executePlannedGraph(runId, request, session, context, graph, observations, riskAssessments, trace);

        session.state().put("last_task_graph", graph);
        session.state().put("task_memory", Map.of(
                "last_task_graph_id", graph.id(),
                "last_task_graph_status", graph.status(),
                "last_task_count", graph.nodes().size()
        ));
        session.state().put("decision_memory", Map.of(
                "last_run_id", runId,
                "last_observation_count", observations.size(),
                "last_status", graph.status()
        ));
        conversationStore.save(session);

        String answer = answerFor(graph, observations);
        List<String> visibleObjectTitles = observations.stream()
                .flatMap(observation -> observation.visibleObjects().stream())
                .map(VisibleObject::title)
                .toList();
        return new MarketingResponse(request.conversationId(), answer, List.of(), visibleObjectTitles,
                metadata(request, session, graph, observations, riskAssessments, trace, capabilities));
    }

    private TaskGraph graphForFeedback(ConversationSession session, PendingAction action) {
        Object value = session.state().get("last_task_graph");
        if (!(value instanceof TaskGraph graph)) {
            return null;
        }
        String taskGraphId = stringValue(action.payload().get("task_graph_id"));
        if (!taskGraphId.isBlank() && !taskGraphId.equals(graph.id())) {
            return null;
        }
        return graph;
    }

    private TaskNode nodeForFeedback(TaskGraph graph, PendingAction action, CapabilityDescriptor capability,
                                     Map<String, Object> inputs) {
        String taskNodeId = stringValue(action.payload().get("task_node_id"));
        if (graph != null && !taskNodeId.isBlank()) {
            TaskNode plannedNode = graph.findNode(taskNodeId).orElse(null);
            if (plannedNode != null) {
                return plannedNode.withInputs(inputs);
            }
        }
        return TaskNode.pending("feedback_node_1", "Resume approved action " + action.id(), capability.name(),
                inputs, List.of());
    }

    private TaskGraph executePlannedGraph(String runId, MarketingRequest request, ConversationSession session,
                                          HarnessContext context, TaskGraph graph, List<Observation> observations,
                                          List<RiskAssessment> riskAssessments, List<HarnessTraceEvent> trace) {
        TaskGraph current = graph.withStatus("running");
        while (current.hasPendingNodes() && !current.isPausedOrTerminal()) {
            List<TaskNode> readyNodes = current.readyNodes();
            if (readyNodes.isEmpty()) {
                Observation blocked = Observation.failed(runId, "graph", "planner",
                        "Task graph has pending nodes but no executable node. Check dependencies for cycles or missing prerequisites.",
                        "TASK_GRAPH_BLOCKED", false);
                observations.add(blocked);
                trace(trace, runId, "task_graph_blocked", "harness", "failed",
                        Map.of("nodeStatuses", current.nodeStatuses()));
                return current.withStatus("failed");
            }

            List<NodeExecutionPlan> executionPlans = new ArrayList<>();
            for (TaskNode node : readyNodes) {
                CapabilityDescriptor capability = capabilityRegistry.find(node.capabilityName()).orElse(null);
                if (capability == null) {
                    Observation failed = Observation.failed(runId, node.id(), node.capabilityName(),
                            "Capability is not registered: " + node.capabilityName(), "CAPABILITY_NOT_FOUND", false);
                    observations.add(failed);
                    current = current.withNode(node.withObservation(TaskNodeStatus.FAILED, failed.id()));
                    trace(trace, runId, "capability_missing", node.capabilityName(), "failed",
                            Map.of("taskNodeId", node.id()));
                    continue;
                }
                Map<String, Object> nodeInputs = enrichInputs(node.inputs(), dependencyObservations(node,
                        observations));
                TaskNode runningNode = node.withInputs(nodeInputs).withStatus(TaskNodeStatus.RUNNING);
                RiskAssessment risk = riskPolicyEngine.assess(capability, runningNode, nodeInputs);
                riskAssessments.add(risk);
                runningNode = runningNode.withRisk(risk.riskLevel());
                current = current.withNode(runningNode);
                executionPlans.add(new NodeExecutionPlan(runningNode, capability));
                trace(trace, runId, "capability_selected", capability.name(), "running",
                        Map.of("taskNodeId", node.id(), "risk", risk.riskLevel(), "requiresApproval",
                                risk.requiresApproval(), "dependsOn", node.dependsOn()));
            }

            TaskGraph batchGraph = current;
            List<CompletableFuture<NodeExecutionResult>> futures = executionPlans.stream()
                    .map(plan -> CompletableFuture.supplyAsync(() -> new NodeExecutionResult(plan.node(),
                            plan.capability(), executeCapability(runId, batchGraph, plan.node(), plan.capability(),
                            context, request, session))))
                    .toList();

            for (CompletableFuture<NodeExecutionResult> future : futures) {
                NodeExecutionResult result = future.join();
                Observation observation = result.observation();
                observations.add(observation);
                commitObservation(session, observation, current);
                TaskNodeStatus status = statusFor(observation);
                TaskNode completedNode = result.node().withObservation(status, observation.id());
                current = current.withNode(completedNode);
                trace(trace, runId, "observation_recorded", result.capability().name(), observation.status(),
                        Map.of("taskNodeId", result.node().id(), "observationId", observation.id()));

                RecoveryDecision recovery = recoveryPolicyEngine.decide(result.capability(), observation);
                trace(trace, runId, "recovery_evaluated", result.capability().name(), recovery.action(),
                        Map.of("reason", recovery.reason(), "fallback", recovery.fallbackCapability()));
                current = applyRecoveryIfNeeded(current, completedNode, result.capability(), observation, recovery,
                        observations, trace, runId);
            }
        }
        return current;
    }

    private TaskGraph applyRecoveryIfNeeded(TaskGraph graph, TaskNode node, CapabilityDescriptor capability,
                                            Observation observation, RecoveryDecision recovery,
                                            List<Observation> observations, List<HarnessTraceEvent> trace,
                                            String runId) {
        if (!TaskNodeStatus.FAILED.equals(statusFor(observation))) {
            return graph;
        }
        if (observation.retryable() && node.retryCount() < 1) {
            TaskNode retryNode = node.incrementRetry().withStatus(TaskNodeStatus.PENDING);
            trace(trace, runId, "node_retry_scheduled", capability.name(), "pending",
                    Map.of("taskNodeId", node.id(), "retryCount", retryNode.retryCount()));
            return graph.withNode(retryNode);
        }
        if ("fallback".equals(recovery.action()) && !recovery.fallbackCapability().isBlank()
                && capabilityRegistry.find(recovery.fallbackCapability()).isPresent()) {
            TaskNode skippedOriginal = node.withObservation(TaskNodeStatus.SKIPPED, observation.id());
            String fallbackNodeId = uniqueNodeId(graph, node.id() + "_fallback");
            TaskNode fallbackNode = TaskNode.planned(
                    fallbackNodeId,
                    "Recover from failed capability " + capability.name(),
                    recovery.fallbackCapability(),
                    Map.of("question", observation.summary(), "source_observations", observation.summary(),
                            "recovery_reason", recovery.reason()),
                    node.dependsOn(),
                    "Fallback capability produced a usable observation or clarification.",
                    node.priority() + 1,
                    recovery.reason()
            );
            trace(trace, runId, "fallback_node_scheduled", recovery.fallbackCapability(), "pending",
                    Map.of("failedTaskNodeId", node.id(), "fallbackTaskNodeId", fallbackNode.id()));
            return graph.withNode(skippedOriginal)
                    .redirectDependents(node.id(), fallbackNode.id())
                    .appendNode(fallbackNode);
        }
        return graph;
    }

    private String uniqueNodeId(TaskGraph graph, String baseId) {
        String value = baseId == null || baseId.isBlank() ? "node_fallback" : baseId;
        String candidate = value;
        int index = 2;
        while (graph.findNode(candidate).isPresent()) {
            candidate = value + "_" + index++;
        }
        return candidate;
    }

    private List<Observation> dependencyObservations(TaskNode node, List<Observation> observations) {
        if (node.dependsOn().isEmpty()) {
            return List.of();
        }
        Set<String> dependencyIds = new HashSet<>(node.dependsOn());
        return observations.stream()
                .filter(observation -> dependencyIds.contains(observation.taskNodeId()))
                .toList();
    }

    private Observation executeCapability(String runId, TaskGraph graph, TaskNode node, CapabilityDescriptor capability,
                                          HarnessContext context, MarketingRequest request,
                                          ConversationSession session) {
        CapabilityProvider provider = capabilityProviders.stream()
                .filter(candidate -> candidate.supports(capability))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            return Observation.failed(runId, node.id(), capability.name(),
                    "No provider supports capability: " + capability.name(), "PROVIDER_NOT_FOUND", false);
        }
        try {
            CapabilityExecutionRequest executionRequest = new CapabilityExecutionRequest(
                    runId,
                    graph.id(),
                    node.id(),
                    request.conversationId(),
                    request.query(),
                    capability,
                    node.inputs(),
                    context.compressedContext(),
                    List.copyOf(session.visibleObjects().values())
            );
            return provider.execute(executionRequest, request);
        }
        catch (RuntimeException ex) {
            return Observation.failed(runId, node.id(), capability.name(),
                    ex.getMessage() == null ? "Capability execution failed." : ex.getMessage(),
                    ex.getClass().getSimpleName(), true);
        }
    }

    private void commitObservation(ConversationSession session, Observation observation, TaskGraph graph) {
        session.addHandoffSummary(ContextSummary.of("capability", observation.id(), observation.status(),
                observation.summary(), Map.of("taskNodeId", observation.taskNodeId(),
                        "capability", observation.capabilityName())));
        observation.messagesToCommit().forEach(session::addMessage);
        observation.visibleObjects().forEach(object -> {
            session.addVisibleObject(object);
            if ("hitl_confirmation".equals(object.type()) && "pending".equals(object.status())) {
                Map<String, Object> pending = new LinkedHashMap<>(object.data());
                pending.put("visible_object_id", object.id());
                pending.putIfAbsent("task_graph_id", graph == null ? "" : graph.id());
                pending.putIfAbsent("task_node_id", observation.taskNodeId());
                pending.putIfAbsent("capability_name", observation.capabilityName());
                String sourceAgent = String.valueOf(pending.getOrDefault("source_agent", "activity_enroll_agent"));
                session.addPendingAction(PendingAction.create(object.id(), object.type(), sourceAgent,
                        observation.id(), object.id(), pending));
            }
        });
        if (!observation.statePatch().isEmpty()) {
            session.state().putAll(observation.statePatch());
        }
    }

    private Map<String, Object> enrichInputs(Map<String, Object> inputs, List<Observation> observations) {
        Map<String, Object> enriched = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        String summaries = observations.stream()
                .map(Observation::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n" + right);
        if (!summaries.isBlank()) {
            enriched.put("source_observations", summaries);
        }
        return enriched;
    }

    private TaskNodeStatus statusFor(Observation observation) {
        return switch (observation.status()) {
            case "succeeded" -> TaskNodeStatus.SUCCEEDED;
            case "waiting_for_approval", "hitl_required" -> TaskNodeStatus.WAITING_FOR_APPROVAL;
            case "waiting_for_user" -> TaskNodeStatus.WAITING_FOR_USER;
            default -> TaskNodeStatus.FAILED;
        };
    }

    private String answerFor(TaskGraph graph, List<Observation> observations) {
        if (observations.isEmpty()) {
            return "我需要更多信息才能继续处理这个营销任务。";
        }
        Observation last = observations.getLast();
        if ("waiting_for_user".equals(last.status())) {
            return "我还需要补充信息：" + String.join("、", last.missingInputs());
        }
        if ("waiting_for_approval".equals(last.status())) {
            return last.summary();
        }
        String combined = observations.stream()
                .map(Observation::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n\n" + right);
        return combined.isBlank() ? "任务已处理完成。" : combined;
    }

    private Map<String, Object> metadata(MarketingRequest request, ConversationSession session, TaskGraph graph,
                                         List<Observation> observations, List<RiskAssessment> riskAssessments,
                                         List<HarnessTraceEvent> trace,
                                         List<CapabilityDescriptor> capabilities) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", harnessDecision(request, graph));
        metadata.put("state", session.state());
        metadata.put("visibleObjects", session.visibleObjects().keySet());
        metadata.put("pendingActions", session.activePendingActionIds());
        metadata.put("harness", Map.of(
                "taskGraphId", graph.id(),
                "status", graph.status(),
                "observationCount", observations.size()
        ));
        metadata.put("taskGraph", graph);
        metadata.put("observations", observations);
        metadata.put("riskAssessments", riskAssessments);
        metadata.put("harnessTrace", trace);
        metadata.put("capabilities", capabilities);
        return metadata;
    }

    private Map<String, Object> harnessDecision(MarketingRequest request, TaskGraph graph) {
        TaskNode first = graph.nodes().isEmpty() ? null : graph.nodes().getFirst();
        CapabilityDescriptor capability = first == null ? null : capabilityRegistry.find(first.capabilityName())
                .orElse(null);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "execute_task_graph");
        decision.put("firstCapability", first == null ? "" : first.capabilityName());
        decision.put("firstProvider", capability == null ? "" : capability.provider());
        decision.put("capabilitySequence", graph.nodes().stream().map(TaskNode::capabilityName).distinct().toList());
        decision.put("taskGraphStatus", graph.status());
        decision.put("plannerRationale", graph.plannerRationale());
        decision.put("answerStrategy", graph.answerStrategy());
        decision.put("inputs", first == null ? Map.of("question", request.query() == null ? "" : request.query())
                : first.inputs());
        return decision;
    }

    private Map<String, Object> feedbackDecision(PendingAction action, List<CapabilityDescriptor> capabilities,
                                                 String status) {
        Map<String, Object> decision = new LinkedHashMap<>();
        String capabilityName = capabilityName(action, capabilities);
        decision.put("action", "human_feedback");
        decision.put("capability", capabilityName);
        decision.put("capabilitySequence", capabilityName.isBlank() ? List.of() : List.of(capabilityName));
        decision.put("provider", capabilityProvider(action, capabilities));
        decision.put("status", status);
        decision.put("inputs", action == null ? Map.of() : action.payload());
        return decision;
    }

    private void trace(List<HarnessTraceEvent> trace, String runId, String eventType, String source, String status,
                       Map<String, Object> data) {
        trace.add(HarnessTraceEvent.of(runId, eventType, source, status, data));
    }

    private MarketingResponse finishFeedback(MarketingRequest request, ConversationSession session, String runId,
                                             String status, String answer, List<Observation> observations,
                                             List<RiskAssessment> riskAssessments, List<HarnessTraceEvent> trace,
                                             List<CapabilityDescriptor> capabilities, PendingAction action) {
        trace(trace, runId, status, "harness", status, action == null ? Map.of() : Map.of("pendingActionId",
                action.id(), "pendingActionStatus", action.status().name()));
        session.addMessage(ConversationMessage.assistant(answer, "harness", status,
                action == null ? Map.of() : Map.of("pendingActionId", action.id())));
        conversationStore.save(session);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", feedbackDecision(action, capabilities, status));
        metadata.put("state", session.state());
        metadata.put("visibleObjects", session.visibleObjects().keySet());
        metadata.put("pendingActions", session.activePendingActionIds());
        metadata.put("harness", Map.of("runId", runId, "status", status, "mode", "human_feedback",
                "observationCount", observations.size()));
        metadata.put("observations", observations);
        metadata.put("riskAssessments", riskAssessments);
        metadata.put("harnessTrace", trace);
        metadata.put("capabilities", capabilities);
        return new MarketingResponse(request.conversationId(), answer, List.of(), List.of(), metadata);
    }

    private CapabilityDescriptor capabilityForPendingAction(PendingAction action,
                                                            List<CapabilityDescriptor> capabilities) {
        String explicit = capabilityName(action, capabilities);
        if (!explicit.isBlank()) {
            return capabilityRegistry.find(explicit).orElse(null);
        }
        return capabilities.stream()
                .filter(capability -> capability.provider().equals(action.sourceAgent()))
                .findFirst()
                .orElse(null);
    }

    private String capabilityName(PendingAction action, List<CapabilityDescriptor> capabilities) {
        if (action == null) {
            return "";
        }
        String capabilityName = stringValue(action.payload().get("capability_name"));
        if (capabilityRegistry.find(capabilityName).isPresent()) {
            return capabilityName;
        }
        String skillName = stringValue(action.payload().get("skill_name"));
        if (capabilityRegistry.find(skillName).isPresent()) {
            return skillName;
        }
        return capabilities.stream()
                .filter(capability -> capability.provider().equals(action.sourceAgent()))
                .map(CapabilityDescriptor::name)
                .findFirst()
                .orElse("");
    }

    private String capabilityProvider(PendingAction action, List<CapabilityDescriptor> capabilities) {
        String capabilityName = capabilityName(action, capabilities);
        return capabilityRegistry.find(capabilityName).map(CapabilityDescriptor::provider).orElse("");
    }

    private Map<String, Object> feedbackInputs(PendingAction action, HumanFeedback feedback) {
        Map<String, Object> inputs = new LinkedHashMap<>(action.payload());
        inputs.putAll(feedback.editedPayload());
        inputs.put("confirmed", true);
        inputs.put("human_feedback_decision", feedback.decision());
        inputs.put("pending_action_id", action.id());
        inputs.put("visible_object_id", action.visibleObjectId());
        if (!inputs.containsKey("capability_name")) {
            inputs.put("capability_name", stringValue(inputs.get("skill_name")));
        }
        return inputs;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record NodeExecutionPlan(TaskNode node, CapabilityDescriptor capability) {
    }

    private record NodeExecutionResult(TaskNode node, CapabilityDescriptor capability, Observation observation) {
    }
}
