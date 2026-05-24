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
import com.example.marketing.core.middleware.CapabilityCallDecision;
import com.example.marketing.core.middleware.HarnessInvocationContext;
import com.example.marketing.core.middleware.HarnessMiddlewareChain;
import com.example.marketing.core.model.ContextSummary;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.HumanFeedback;
import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.PendingActionStateMachine;
import com.example.marketing.core.model.VisibleObject;
import com.example.marketing.core.observation.ActionProposal;
import com.example.marketing.core.observation.Observation;
import com.example.marketing.core.observation.ObservationEvaluation;
import com.example.marketing.core.observation.ObservationEvaluator;
import com.example.marketing.core.observability.AgentTelemetry;
import com.example.marketing.core.policy.RiskAssessment;
import com.example.marketing.core.recovery.RecoveryDecision;
import com.example.marketing.core.recovery.RecoveryPolicyEngine;
import com.example.marketing.core.state.ConversationLockManager;
import com.example.marketing.core.state.ConversationSession;
import com.example.marketing.core.state.ConversationStore;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;
import com.example.marketing.core.task.TaskNodeStatus;
import com.example.marketing.core.workspace.AgentWorkspace;

@Service
public class MarketingHarness {
    private final ConversationStore conversationStore;
    private final ConversationLockManager conversationLockManager;
    private final CapabilityRegistry capabilityRegistry;
    private final List<CapabilityProvider> capabilityProviders;
    private final ContextAssembler contextAssembler;
    private final TaskPlanner taskPlanner;
    private final RecoveryPolicyEngine recoveryPolicyEngine;
    private final AuditEventPublisher auditEventPublisher;
    private final AgentTelemetry telemetry;
    private final PendingActionStateMachine pendingActionStateMachine;
    private final TaskGraphValidator taskGraphValidator;
    private final ObservationEvaluator observationEvaluator;
    private final AgentWorkspace workspace;
    private final HarnessMiddlewareChain middlewareChain;

    public MarketingHarness(ConversationStore conversationStore,
                            ConversationLockManager conversationLockManager,
                            CapabilityRegistry capabilityRegistry,
                            List<CapabilityProvider> capabilityProviders,
                            ContextAssembler contextAssembler,
                            TaskPlanner taskPlanner,
                            RecoveryPolicyEngine recoveryPolicyEngine,
                            AuditEventPublisher auditEventPublisher,
                            AgentTelemetry telemetry,
                            PendingActionStateMachine pendingActionStateMachine,
                            TaskGraphValidator taskGraphValidator,
                            ObservationEvaluator observationEvaluator,
                            AgentWorkspace workspace,
                            HarnessMiddlewareChain middlewareChain) {
        this.conversationStore = conversationStore;
        this.conversationLockManager = conversationLockManager;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityProviders = capabilityProviders == null ? List.of() : List.copyOf(capabilityProviders);
        this.contextAssembler = contextAssembler;
        this.taskPlanner = taskPlanner;
        this.recoveryPolicyEngine = recoveryPolicyEngine;
        this.auditEventPublisher = auditEventPublisher;
        this.telemetry = telemetry;
        this.pendingActionStateMachine = pendingActionStateMachine;
        this.taskGraphValidator = taskGraphValidator;
        this.observationEvaluator = observationEvaluator;
        this.workspace = workspace;
        this.middlewareChain = middlewareChain;
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
        List<ObservationEvaluation> evaluations = new ArrayList<>();
        List<RiskAssessment> riskAssessments = new ArrayList<>();
        List<CapabilityDescriptor> capabilities = capabilityRegistry.list();
        HarnessInvocationContext invocation = new HarnessInvocationContext(runId, request, session, trace);
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
            auditEventPublisher.publish(AuditEvent.of("pending_action_expired", request.conversationId(), "harness",
                    pendingActionAuditData(expired)));
            return finishFeedback(request, session, runId, "feedback_expired",
                    "这个确认动作已经过期，请重新发起。", observations, riskAssessments, trace, capabilities, expired);
        }
        if (feedback.isReject()) {
            PendingAction rejected = pendingActionStateMachine.reject(action, request.userId());
            session.updatePendingAction(rejected);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "rejected");
            auditEventPublisher.publish(AuditEvent.of("hitl_rejected", request.conversationId(), "harness",
                    Map.of("pendingActionId", action.id())));
            auditEventPublisher.publish(AuditEvent.of("pending_action_rejected", request.conversationId(),
                    "harness", pendingActionAuditData(rejected)));
            return finishFeedback(request, session, runId, "feedback_rejected",
                    "已取消这次待确认动作。", observations, riskAssessments, trace, capabilities, rejected);
        }
        if (feedback.isEdit()) {
            PendingAction edited = pendingActionStateMachine.edit(action, feedback.editedPayload(), request.userId());
            session.updatePendingAction(edited);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "edited");
            auditEventPublisher.publish(AuditEvent.of("hitl_edited", request.conversationId(), "harness",
                    Map.of("pendingActionId", action.id())));
            auditEventPublisher.publish(AuditEvent.of("pending_action_edited", request.conversationId(),
                    "harness", pendingActionAuditData(edited)));
            return finishFeedback(request, session, runId, "feedback_edited",
                    "已记录你的调整，请重新确认后再执行。", observations, riskAssessments, trace, capabilities, edited);
        }

        PendingAction approved = pendingActionStateMachine.approve(action, request.userId());
        session.updatePendingAction(approved);
        session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
        auditEventPublisher.publish(AuditEvent.of("hitl_approved", request.conversationId(), "harness",
                Map.of("pendingActionId", action.id(), "sourceAgent", action.sourceAgent())));
        auditEventPublisher.publish(AuditEvent.of("pending_action_approved", request.conversationId(), "harness",
                pendingActionAuditData(approved)));

        CapabilityDescriptor capability = capabilityForPendingAction(approved, capabilities);
        if (capability == null) {
            return finishFeedback(request, session, runId, "feedback_capability_missing",
                    "没有找到可以继续执行这个确认动作的能力。", observations, riskAssessments, trace, capabilities,
                    approved);
        }
        Map<String, Object> inputs = feedbackInputs(approved, feedback);
        TaskGraph graph = graphForFeedback(session, approved);
        TaskNode node = nodeForFeedback(graph, approved, capability, inputs).withStatus(TaskNodeStatus.RUNNING);
        CapabilityCallDecision permission = middlewareChain.beforeCapabilityCall(invocation,
                graph == null ? new TaskGraph("tg_feedback_permission", "feedback", List.of(node), "running") : graph,
                node, capability);
        RiskAssessment risk = permission.riskAssessment() == null
                ? new RiskAssessment(capability.name(), capability.riskLevel(), false, true,
                "No middleware risk assessment returned.")
                : permission.riskAssessment();
        riskAssessments.add(risk);
        node = node.withRisk(risk.riskLevel());
        graph = graph == null
                ? new TaskGraph("tg_feedback_" + UUID.randomUUID().toString().substring(0, 8),
                "Resume pending action " + approved.id(), List.of(node), "running")
                : graph.withNode(node).withStatus("running");
        HarnessContext context = assembleContext(invocation, capabilities);
        Observation observation = executeCapability(runId, graph, node, capability, context, request, session);
        middlewareChain.afterCapabilityCall(invocation, graph, node, capability, observation);
        observation = commitObservation(invocation, observation, graph);
        observations.add(observation);
        ObservationEvaluation evaluation = observationEvaluator.evaluate(capability, node, observation);
        evaluations.add(evaluation);
        TaskNodeStatus status = statusFor(observation);
        TaskNode completedNode = node.withObservation(status, observation.id());
        graph = graph.withNode(completedNode);
        trace(trace, runId, "feedback_capability_executed", capability.name(), observation.status(),
                Map.of("pendingActionId", approved.id(), "observationId", observation.id()));
        if (TaskNodeStatus.SUCCEEDED.equals(status)) {
            PendingAction current = session.pendingActions().getOrDefault(approved.id(), approved);
            PendingAction executed = pendingActionStateMachine.executed(current, request.userId());
            session.updatePendingAction(executed);
            session.updateVisibleObjectStatus(approved.visibleObjectId(), "executed");
            auditEventPublisher.publish(AuditEvent.of("pending_action_executed", request.conversationId(),
                    "harness", pendingActionAuditData(executed, observation)));
        }
        RecoveryDecision recovery = recoveryPolicyEngine.decide(capability, completedNode, observation);
        trace(trace, runId, "feedback_recovery_evaluated", capability.name(), recovery.action(),
                Map.of("reason", recovery.reason(), "fallback", recovery.fallbackCapability(),
                        "evaluation", evaluation));
        graph = applyRecoveryIfNeeded(graph, completedNode, capability, observation, recovery, observations, trace,
                runId, request, context);
        if (graph.hasPendingNodes() && !graph.isPausedOrTerminal()) {
            HarnessContext resumedContext = assembleContext(invocation, capabilities);
            graph = executePlannedGraph(invocation, resumedContext, graph, observations, evaluations,
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
                metadata(request, session, graph, observations, evaluations, riskAssessments, trace, capabilities,
                        null));
    }

    private MarketingResponse runLocked(MarketingRequest request) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        session.addMessage(ConversationMessage.user(request.query() == null ? "" : request.query(),
                Map.of("variables", request.variables() == null ? Map.of() : request.variables())));

        List<CapabilityDescriptor> capabilities = capabilityRegistry.list();
        List<HarnessTraceEvent> trace = new ArrayList<>();
        HarnessInvocationContext invocation = new HarnessInvocationContext(runId, request, session, trace);
        HarnessContext context = assembleContext(invocation, capabilities);
        List<Observation> observations = new ArrayList<>();
        List<ObservationEvaluation> evaluations = new ArrayList<>();
        List<RiskAssessment> riskAssessments = new ArrayList<>();

        TaskGraph resumed = resumeWaitingGraphIfPossible(request, session, capabilities, trace, runId);
        if (resumed != null) {
            resumed = executePlannedGraph(invocation, context, resumed, observations, evaluations,
                    riskAssessments, trace);
            session.state().put("last_task_graph", resumed);
            session.state().put("task_memory", Map.of(
                    "last_task_graph_id", resumed.id(),
                    "last_task_graph_status", resumed.status(),
                    "last_task_count", resumed.nodes().size()
            ));
            session.state().put("decision_memory", Map.of(
                    "last_run_id", runId,
                    "last_observation_count", observations.size(),
                    "last_status", resumed.status(),
                    "resumed_waiting_graph", true
            ));
            conversationStore.save(session);
            return new MarketingResponse(request.conversationId(), answerFor(resumed, observations), List.of(),
                    observations.stream()
                            .flatMap(observation -> observation.visibleObjects().stream())
                            .map(VisibleObject::title)
                            .toList(),
                    metadata(request, session, resumed, observations, evaluations, riskAssessments, trace,
                            capabilities, null));
        }

        middlewareChain.beforePlan(invocation, context);
        middlewareChain.beforeModelCall(invocation, "task-graph-planning");
        TaskGraph graph = taskPlanner.plan(request, context);
        middlewareChain.afterModelCall(invocation, "task-graph-planning", graph.status());
        middlewareChain.afterPlan(invocation, graph);
        TaskGraphValidationResult validation = taskGraphValidator.validateAndRepair(graph);
        graph = validation.graph() == null ? graph.withStatus("failed") : validation.graph();
        trace(trace, runId, "task_graph_planned", "planner", graph.status(),
                Map.of("taskGraphId", graph.id(), "nodeCount", graph.nodes().size()));
        trace(trace, runId, "task_graph_validated", "validator", validation.valid() ? "valid" : "invalid",
                Map.of("errors", validation.errors(), "warnings", validation.warnings(),
                        "repairs", validation.repairs()));
        telemetry.event("task_graph_planned", "harness", graph.status(),
                Map.of("taskGraphId", graph.id(), "nodeCount", graph.nodes().size()));
        auditEventPublisher.publish(AuditEvent.of("task_graph_planned", request.conversationId(), "harness",
                Map.of("runId", runId, "taskGraphId", graph.id(), "nodeCount", graph.nodes().size())));

        if (validation.valid()) {
            graph = executePlannedGraph(invocation, context, graph, observations, evaluations,
                    riskAssessments, trace);
        }

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
                metadata(request, session, graph, observations, evaluations, riskAssessments, trace, capabilities,
                        validation));
    }

    private HarnessContext assembleContext(HarnessInvocationContext invocation,
                                           List<CapabilityDescriptor> capabilities) {
        middlewareChain.beforeContextAssemble(invocation);
        HarnessContext context = contextAssembler.assemble(invocation.request(), invocation.session(), capabilities);
        middlewareChain.afterContextAssemble(invocation, context);
        return context;
    }

    private TaskGraph resumeWaitingGraphIfPossible(MarketingRequest request, ConversationSession session,
                                                   List<CapabilityDescriptor> capabilities,
                                                   List<HarnessTraceEvent> trace, String runId) {
        Object value = session.state().get("last_task_graph");
        if (!(value instanceof TaskGraph graph) || !"waiting_for_user".equals(graph.status())) {
            return null;
        }
        TaskNode waitingNode = graph.nodes().stream()
                .filter(node -> TaskNodeStatus.WAITING_FOR_USER.equals(node.status()))
                .findFirst()
                .orElse(null);
        if (waitingNode == null) {
            return null;
        }
        CapabilityDescriptor capability = capabilityRegistry.find(waitingNode.capabilityName()).orElse(null);
        if (capability == null) {
            return null;
        }
        Map<String, Object> mergedInputs = new LinkedHashMap<>(waitingNode.inputs());
        if (request.variables() != null) {
            mergedInputs.putAll(request.variables());
        }
        List<String> missing = missingRequiredInputs(capability, mergedInputs);
        if (missing.size() == 1 && request.query() != null && !request.query().isBlank()
                && !mergedInputs.containsKey(missing.getFirst())) {
            mergedInputs.put(missing.getFirst(), request.query());
            missing = missingRequiredInputs(capability, mergedInputs);
        }
        if (!missing.isEmpty()) {
            trace(trace, runId, "waiting_graph_still_missing_inputs", capability.name(), "waiting_for_user",
                    Map.of("taskGraphId", graph.id(), "taskNodeId", waitingNode.id(), "missingInputs", missing));
            return null;
        }
        TaskNode resumedNode = waitingNode.withInputs(mergedInputs).withStatus(TaskNodeStatus.PENDING);
        TaskGraph resumed = graph.withNode(resumedNode).withStatus("running");
        session.state().remove("waiting_node");
        trace(trace, runId, "waiting_graph_resumed", capability.name(), "running",
                Map.of("taskGraphId", resumed.id(), "taskNodeId", resumedNode.id()));
        return resumed;
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
        String nodeId = taskNodeId.isBlank() ? "feedback_node_1" : taskNodeId;
        return TaskNode.pending(nodeId, "Resume approved action " + action.id(), capability.name(),
                inputs, List.of());
    }

    private TaskGraph executePlannedGraph(HarnessInvocationContext invocation, HarnessContext context,
                                          TaskGraph graph, List<Observation> observations,
                                          List<ObservationEvaluation> evaluations,
                                          List<RiskAssessment> riskAssessments, List<HarnessTraceEvent> trace) {
        String runId = invocation.runId();
        MarketingRequest request = invocation.request();
        ConversationSession session = invocation.session();
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
                List<String> missingInputs = missingRequiredInputs(capability, nodeInputs);
                if (!missingInputs.isEmpty()) {
                    Observation waiting = waitingForUserObservation(runId, current, runningNode, capability,
                            missingInputs);
                    waiting = commitObservation(invocation, waiting, current);
                    observations.add(waiting);
                    TaskNode waitingNode = runningNode.withObservation(TaskNodeStatus.WAITING_FOR_USER, waiting.id());
                    current = current.withNode(waitingNode);
                    trace(trace, runId, "node_waiting_for_user", capability.name(), waiting.status(),
                            Map.of("taskNodeId", node.id(), "missingInputs", missingInputs,
                                    "observationId", waiting.id()));
                    continue;
                }
                CapabilityCallDecision permission = middlewareChain.beforeCapabilityCall(invocation, current,
                        runningNode, capability);
                RiskAssessment risk = permission.riskAssessment() == null
                        ? new RiskAssessment(capability.name(), capability.riskLevel(), false, true,
                        "No middleware risk assessment returned.")
                        : permission.riskAssessment();
                riskAssessments.add(risk);
                runningNode = runningNode.withRisk(risk.riskLevel());
                current = current.withNode(runningNode);
                if (risk.requiresApproval()) {
                    Observation waiting = waitingForApprovalObservation(runId, current, runningNode, capability, risk);
                    middlewareChain.onHumanApprovalRequired(invocation, current, runningNode, capability, waiting);
                    waiting = commitObservation(invocation, waiting, current);
                    observations.add(waiting);
                    TaskNode waitingNode = runningNode.withObservation(TaskNodeStatus.WAITING_FOR_APPROVAL,
                            waiting.id());
                    current = current.withNode(waitingNode);
                    trace(trace, runId, "hitl_boundary_enforced", capability.name(), waiting.status(),
                            Map.of("taskNodeId", node.id(), "risk", risk.riskLevel(),
                                    "observationId", waiting.id()));
                    continue;
                }
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
                middlewareChain.afterCapabilityCall(invocation, current, result.node(), result.capability(),
                        observation);
                observation = commitObservation(invocation, observation, current);
                observations.add(observation);
                ObservationEvaluation evaluation = observationEvaluator.evaluate(result.capability(), result.node(),
                        observation);
                evaluations.add(evaluation);
                TaskNodeStatus status = statusFor(observation);
                TaskNode completedNode = result.node().withObservation(status, observation.id());
                current = current.withNode(completedNode);
                trace(trace, runId, "observation_recorded", result.capability().name(), observation.status(),
                        Map.of("taskNodeId", result.node().id(), "observationId", observation.id()));

                RecoveryDecision recovery = recoveryPolicyEngine.decide(result.capability(), completedNode,
                        observation);
                trace(trace, runId, "recovery_evaluated", result.capability().name(), recovery.action(),
                        Map.of("reason", recovery.reason(), "fallback", recovery.fallbackCapability(),
                                "evaluation", evaluation));
                current = applyRecoveryIfNeeded(current, completedNode, result.capability(), observation, recovery,
                        observations, trace, runId, request, context);
            }
        }
        return current;
    }

    private Observation waitingForUserObservation(String runId, TaskGraph graph, TaskNode node,
                                                  CapabilityDescriptor capability, List<String> missingInputs) {
        return new Observation(null, runId, node.id(), capability.name(), "waiting_for_user",
                "我需要补充信息才能继续执行 " + capability.name() + "：" + String.join("、", missingInputs),
                Map.of("expected_schema", capability.inputSchema(), "task_graph_id", graph.id(),
                        "task_node_id", node.id()),
                Map.of(), 0.2, missingInputs, "low", false, null, "MISSING_INPUTS", false,
                List.of(),
                List.of(ConversationMessage.assistant("我需要补充信息：" + String.join("、", missingInputs),
                        "harness", "waiting_for_user",
                        Map.of("taskGraphId", graph.id(), "taskNodeId", node.id(),
                                "missingInputs", missingInputs))),
                Map.of("waiting_node", Map.of(
                        "task_graph_id", graph.id(),
                        "task_node_id", node.id(),
                        "capability_name", capability.name(),
                        "missing_inputs", missingInputs,
                        "expected_schema", capability.inputSchema()
                )));
    }

    private Observation waitingForApprovalObservation(String runId, TaskGraph graph, TaskNode node,
                                                      CapabilityDescriptor capability, RiskAssessment risk) {
        String cardId = "confirm_" + node.id() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "pending_action:" + graph.id() + ":" + node.id() + ":" + capability.name();
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("capability_name", capability.name());
        diff.put("goal", node.goal());
        diff.put("inputs", node.inputs());
        diff.put("side_effects", capability.sideEffects());
        diff.put("permissions", capability.permissions());
        Map<String, Object> payload = new LinkedHashMap<>(node.inputs());
        payload.put("source_agent", capability.provider().isBlank() ? "harness" : capability.provider());
        payload.put("capability_name", capability.name());
        payload.put("task_graph_id", graph.id());
        payload.put("task_node_id", node.id());
        payload.put("idempotency_key", idempotencyKey);
        payload.put("risk_level", capability.riskLevel());
        payload.put("approval_source", "harness_risk_policy");
        payload.put("execution_diff", diff);
        payload.put("actions", List.of(
                Map.of("id", "confirm", "label", "确认执行", "enabled", true),
                Map.of("id", "cancel", "label", "取消", "enabled", true),
                Map.of("id", "modify", "label", "我要调整", "enabled", true)
        ));
        String summary = "该动作会产生业务副作用，需要你确认后才能执行：" + node.goal();
        VisibleObject card = VisibleObject.of(cardId, "hitl_confirmation", "确认执行：" + capability.name(),
                "pending", summary, payload);
        ActionProposal proposal = new ActionProposal(cardId, "hitl_confirmation", capability.name(), summary,
                payload, capability.riskLevel());
        return new Observation(
                null,
                runId,
                node.id(),
                capability.name(),
                "waiting_for_approval",
                summary,
                Map.of("risk_reason", risk.reason(), "harness_boundary", "provider_not_invoked_before_approval"),
                Map.of("idempotency_key", idempotencyKey, "execution_diff", diff),
                0.9,
                List.of(),
                capability.riskLevel(),
                true,
                proposal,
                "",
                false,
                List.of(card),
                List.of(ConversationMessage.assistant(summary, "harness", "waiting_for_approval",
                        Map.of("pendingActionId", cardId, "capability", capability.name()))),
                Map.of("current_task", Map.of("type", capability.name(), "status", "waiting_for_approval",
                        "task_graph_id", graph.id(), "task_node_id", node.id()))
        );
    }

    private TaskGraph applyRecoveryIfNeeded(TaskGraph graph, TaskNode node, CapabilityDescriptor capability,
                                            Observation observation, RecoveryDecision recovery,
                                            List<Observation> observations, List<HarnessTraceEvent> trace,
                                            String runId, MarketingRequest request, HarnessContext context) {
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
        if ("replan".equals(recovery.action())) {
            TaskGraph replanned = taskPlanner.replan(request, context, graph, observations, recovery.reason());
            TaskGraphValidationResult validation = taskGraphValidator.validateAndRepair(replanned);
            trace(trace, runId, "replan_scheduled", "planner", validation.valid() ? "planned" : "failed",
                    Map.of("previousTaskGraphId", graph.id(), "nextTaskGraphId", replanned.id(),
                            "reason", recovery.reason(), "validationErrors", validation.errors(),
                            "validationRepairs", validation.repairs()));
            if (!validation.valid()) {
                return graph.withNode(node.withObservation(TaskNodeStatus.FAILED, observation.id()))
                        .withStatus("failed");
            }
            return validation.graph().withStatus("running");
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
                    context.memory().workspaceRefs(),
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

    private Observation commitObservation(HarnessInvocationContext invocation, Observation rawObservation,
                                         TaskGraph graph) {
        Observation observation = middlewareChain.beforeObservationCommit(invocation, graph, rawObservation);
        ConversationSession session = invocation.session();
        String workspacePath = workspacePathFor(observation);
        session.addHandoffSummary(ContextSummary.of("capability", observation.id(), observation.status(),
                observation.summary(), Map.of("taskNodeId", observation.taskNodeId(),
                        "capability", observation.capabilityName(),
                        "workspace_path", workspacePath)));
        observation.messagesToCommit().forEach(session::addMessage);
        observation.visibleObjects().forEach(object -> {
            session.addVisibleObject(object);
            if ("hitl_confirmation".equals(object.type()) && "pending".equals(object.status())) {
                Map<String, Object> pending = new LinkedHashMap<>(object.data());
                pending.put("visible_object_id", object.id());
                pending.putIfAbsent("task_graph_id", graph == null ? "" : graph.id());
                pending.putIfAbsent("task_node_id", observation.taskNodeId());
                pending.putIfAbsent("capability_name", observation.capabilityName());
                PendingAction action = buildPendingAction(session, graph, observation, object, pending);
                session.addPendingAction(action);
                auditEventPublisher.publish(AuditEvent.of("pending_action_created", session.conversationId(),
                        "harness", pendingActionAuditData(action, observation)));
            }
        });
        if (!observation.statePatch().isEmpty()) {
            session.state().putAll(observation.statePatch());
        }
        syncWorkspaceRefs(session, graph, observation, workspacePath);
        middlewareChain.afterObservationCommit(invocation, graph, observation);
        return observation;
    }

    private void syncWorkspaceRefs(ConversationSession session, TaskGraph graph, Observation observation,
                                   String workspacePath) {
        Map<String, Object> refs = new LinkedHashMap<>();
        Object existing = session.state().get("workspace_refs");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> refs.put(String.valueOf(key), value));
        }
        List<Object> observationRefs = new ArrayList<>();
        Object existingObservations = refs.get("observations");
        if (existingObservations instanceof List<?> list) {
            observationRefs.addAll(list);
        }
        Map<String, Object> observationRef = new LinkedHashMap<>();
        observationRef.put("observation_id", observation.id());
        observationRef.put("run_id", observation.runId());
        observationRef.put("task_graph_id", graph == null ? "" : graph.id());
        observationRef.put("task_node_id", observation.taskNodeId());
        observationRef.put("capability_name", observation.capabilityName());
        observationRef.put("workspace_path", workspacePath);
        Map<String, Object> workspaceRefs = workspaceRefsFor(observation);
        observationRef.put("evidence_paths", pathsFrom(workspaceRefs, "evidence"));
        observationRef.put("artifact_paths", pathsFrom(workspaceRefs, "artifacts"));
        observationRefs.add(observationRef);
        refs.put("observations", observationRefs);
        refs.put("latest_observation", observationRef);
        if (!observation.artifacts().isEmpty()) {
            refs.put("latest_artifact_observation_path", workspacePath);
            refs.put("latest_artifact_paths", pathsFrom(workspaceRefs, "artifacts"));
        }
        session.state().put("workspace_refs", refs);
        session.state().put("artifact_memory", Map.of("latest_workspace_path", workspacePath,
                "latest_observation_id", observation.id(),
                "latest_capability_name", observation.capabilityName()));
    }

    private String workspacePathFor(Observation observation) {
        Object value = observation.artifacts().get("workspace_observation_path");
        if (value == null) {
            value = workspaceRefsFor(observation).get("observation_path");
        }
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> workspaceRefsFor(Observation observation) {
        Object value = observation.evidence().get("workspace_refs");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> refs = new LinkedHashMap<>();
            map.forEach((key, item) -> refs.put(String.valueOf(key), item));
            return refs;
        }
        return Map.of();
    }

    private List<Object> pathsFrom(Map<String, Object> workspaceRefs, String key) {
        Object value = workspaceRefs.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(map -> map.get("workspace_path"))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private PendingAction buildPendingAction(ConversationSession session, TaskGraph graph, Observation observation,
                                             VisibleObject object, Map<String, Object> rawPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(rawPayload == null ? Map.of() : rawPayload);
        String capabilityName = firstNonBlank(stringValue(payload.get("capability_name")),
                stringValue(payload.get("skill_name")), observation.capabilityName());
        if (capabilityName.isBlank() || capabilityRegistry.find(capabilityName).isEmpty()) {
            throw new IllegalStateException("Pending action is missing a registered capability binding: "
                    + object.id());
        }
        String graphId = firstNonBlank(stringValue(payload.get("task_graph_id")),
                graph == null ? "" : graph.id());
        if (graphId.isBlank()) {
            throw new IllegalStateException("Pending action is missing task graph binding: " + object.id());
        }
        String taskNodeId = firstNonBlank(stringValue(payload.get("task_node_id")),
                observation.taskNodeId() + "_approved_execution");
        if (taskNodeId.isBlank()) {
            throw new IllegalStateException("Pending action is missing task node binding: " + object.id());
        }
        String idempotencyKey = firstNonBlank(stringValue(payload.get("idempotency_key")),
                "pending_action:" + graphId + ":" + taskNodeId + ":" + capabilityName);
        CapabilityDescriptor capability = capabilityRegistry.find(capabilityName)
                .orElseThrow(() -> new IllegalStateException("Unknown pending action capability: " + capabilityName));
        payload.put("visible_object_id", object.id());
        payload.put("task_graph_id", graphId);
        payload.put("task_node_id", taskNodeId);
        payload.put("capability_name", capabilityName);
        payload.put("idempotency_key", idempotencyKey);
        payload.putIfAbsent("approval_source", "harness_pending_action_builder");
        payload.put("source_observation_id", observation.id());
        payload.put("source_task_node_id", observation.taskNodeId());
        payload.put("source_capability_name", observation.capabilityName());
        payload.put("source_provider", capability.provider());
        String sourceAgent = firstNonBlank(stringValue(payload.get("source_agent")), capability.provider(), "harness");
        return PendingAction.create(object.id(), object.type(), sourceAgent, observation.id(), object.id(), payload);
    }

    private Map<String, Object> pendingActionAuditData(PendingAction action) {
        return pendingActionAuditData(action, null);
    }

    private Map<String, Object> pendingActionAuditData(PendingAction action, Observation observation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingActionId", action.id());
        data.put("status", action.status().name());
        data.put("visibleObjectId", action.visibleObjectId());
        data.put("sourceAgent", action.sourceAgent());
        data.put("invocationId", action.invocationId());
        data.put("taskGraphId", stringValue(action.payload().get("task_graph_id")));
        data.put("taskNodeId", stringValue(action.payload().get("task_node_id")));
        data.put("capabilityName", stringValue(action.payload().get("capability_name")));
        data.put("idempotencyKey", stringValue(action.payload().get("idempotency_key")));
        data.put("approvalSource", stringValue(action.payload().get("approval_source")));
        if (observation != null) {
            data.put("observationId", observation.id());
            data.put("observationStatus", observation.status());
            data.put("operationReceipt", observation.artifacts().getOrDefault("operation_receipt", Map.of()));
        }
        return data;
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

    private List<String> missingRequiredInputs(CapabilityDescriptor capability, Map<String, Object> inputs) {
        if (capability.requiredInputs().isEmpty()) {
            return List.of();
        }
        return capability.requiredInputs().stream()
                .filter(input -> !approvalRuntimeInput(capability, input))
                .filter(input -> {
                    Object value = inputs == null ? null : inputs.get(input);
                    return value == null || value.toString().isBlank();
                })
                .toList();
    }

    private boolean approvalRuntimeInput(CapabilityDescriptor capability, String input) {
        return capability.requiresHumanApproval()
                && ("pending_action_approved".equals(input) || "idempotency_key".equals(input));
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
                                         List<Observation> observations,
                                         List<ObservationEvaluation> evaluations,
                                         List<RiskAssessment> riskAssessments,
                                         List<HarnessTraceEvent> trace,
                                         List<CapabilityDescriptor> capabilities,
                                         TaskGraphValidationResult validation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", jsonValue(harnessDecision(request, graph)));
        metadata.put("state", jsonValue(session.state()));
        metadata.put("visibleObjects", List.copyOf(session.visibleObjects().keySet()));
        metadata.put("pendingActions", session.activePendingActionIds());
        metadata.put("harness", Map.of(
                "taskGraphId", graph.id(),
                "status", graph.status(),
                "observationCount", observations.size()
        ));
        metadata.put("taskGraph", taskGraphView(graph));
        metadata.put("validation", validationView(validation));
        metadata.put("observations", observations.stream().map(this::observationView).toList());
        metadata.put("observationEvaluations", evaluations.stream().map(this::evaluationView).toList());
        metadata.put("riskAssessments", riskAssessments.stream().map(this::riskAssessmentView).toList());
        metadata.put("harnessTrace", trace.stream().map(this::traceView).toList());
        metadata.put("capabilities", capabilities.stream().map(this::capabilityView).toList());
        metadata.put("middleware", middlewareChain.names());
        metadata.put("workspaceRefs", jsonValue(session.state().getOrDefault("workspace_refs", Map.of())));
        metadata.put("workspace", workspace.list(request.conversationId(), "/").stream().map(this::workspaceEntryView)
                .toList());
        return metadata;
    }

    private Map<String, Object> workspaceEntryView(com.example.marketing.core.workspace.WorkspaceEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("path", entry.path());
        view.put("directory", entry.directory());
        view.put("size", entry.size());
        view.put("metadata", jsonValue(entry.metadata()));
        view.put("updatedAt", entry.updatedAt() == null ? "" : entry.updatedAt().toString());
        return view;
    }

    private Map<String, Object> validationView(TaskGraphValidationResult validation) {
        if (validation == null) {
            return Map.of();
        }
        return Map.of(
                "valid", validation.valid(),
                "errors", validation.errors(),
                "warnings", validation.warnings(),
                "repairs", validation.repairs()
        );
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

    private Map<String, Object> taskGraphView(TaskGraph graph) {
        if (graph == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", graph.id());
        view.put("userGoal", graph.userGoal());
        view.put("status", graph.status());
        view.put("plannerRationale", graph.plannerRationale());
        view.put("answerStrategy", graph.answerStrategy());
        view.put("nodes", graph.nodes().stream().map(this::taskNodeView).toList());
        return view;
    }

    private Map<String, Object> taskNodeView(TaskNode node) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", node.id());
        view.put("goal", node.goal());
        view.put("capabilityName", node.capabilityName());
        view.put("inputs", jsonValue(node.inputs()));
        view.put("dependsOn", node.dependsOn());
        view.put("completionCriteria", node.completionCriteria());
        view.put("priority", node.priority());
        view.put("plannerRationale", node.plannerRationale());
        view.put("retryCount", node.retryCount());
        view.put("status", node.status().name());
        view.put("riskLevel", node.riskLevel());
        view.put("observationId", node.observationId());
        return view;
    }

    private Map<String, Object> observationView(Observation observation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", observation.id());
        view.put("runId", observation.runId());
        view.put("taskNodeId", observation.taskNodeId());
        view.put("capabilityName", observation.capabilityName());
        view.put("status", observation.status());
        view.put("summary", observation.summary());
        view.put("evidence", jsonValue(observation.evidence()));
        view.put("artifacts", jsonValue(observation.artifacts()));
        view.put("confidence", observation.confidence());
        view.put("missingInputs", observation.missingInputs());
        view.put("riskLevel", observation.riskLevel());
        view.put("requiresApproval", observation.requiresApproval());
        view.put("actionProposal", actionProposalView(observation.actionProposal()));
        view.put("errorType", observation.errorType());
        view.put("retryable", observation.retryable());
        view.put("visibleObjects", observation.visibleObjects().stream().map(this::visibleObjectView).toList());
        view.put("messagesToCommit", jsonValue(observation.messagesToCommit()));
        view.put("statePatch", jsonValue(observation.statePatch()));
        return view;
    }

    private Map<String, Object> actionProposalView(ActionProposal proposal) {
        if (proposal == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", proposal.id());
        view.put("type", proposal.type());
        view.put("sourceCapability", proposal.sourceCapability());
        view.put("summary", proposal.summary());
        view.put("payload", jsonValue(proposal.payload()));
        view.put("riskLevel", proposal.riskLevel());
        return view;
    }

    private Map<String, Object> visibleObjectView(VisibleObject object) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", object.id());
        view.put("type", object.type());
        view.put("title", object.title());
        view.put("status", object.status());
        view.put("summary", object.summary());
        view.put("data", jsonValue(object.data()));
        view.put("createdAt", object.createdAt() == null ? "" : object.createdAt().toString());
        return view;
    }

    private Map<String, Object> riskAssessmentView(RiskAssessment risk) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("capabilityName", risk.capabilityName());
        view.put("riskLevel", risk.riskLevel());
        view.put("requiresApproval", risk.requiresApproval());
        view.put("readOnly", risk.readOnly());
        view.put("reason", risk.reason());
        return view;
    }

    private Map<String, Object> evaluationView(ObservationEvaluation evaluation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("observationId", evaluation.observationId());
        view.put("taskNodeId", evaluation.taskNodeId());
        view.put("capabilityName", evaluation.capabilityName());
        view.put("sufficient", evaluation.sufficient());
        view.put("grounded", evaluation.grounded());
        view.put("usable", evaluation.usable());
        view.put("needsFallback", evaluation.needsFallback());
        view.put("needsReplan", evaluation.needsReplan());
        view.put("reasons", evaluation.reasons());
        return view;
    }

    private Map<String, Object> traceView(HarnessTraceEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", event.runId());
        view.put("eventType", event.eventType());
        view.put("source", event.source());
        view.put("status", event.status());
        view.put("data", jsonValue(event.data()));
        view.put("createdAt", event.createdAt() == null ? "" : event.createdAt().toString());
        return view;
    }

    private Map<String, Object> capabilityView(CapabilityDescriptor capability) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", capability.name());
        view.put("description", capability.description());
        view.put("requiredInputs", capability.requiredInputs());
        view.put("outputContract", capability.outputContract());
        view.put("permissions", capability.permissions());
        view.put("sideEffects", capability.sideEffects());
        view.put("requiresHumanApproval", capability.requiresHumanApproval());
        view.put("riskLevel", capability.riskLevel());
        view.put("provider", capability.provider());
        view.put("executionMode", capability.executionMode());
        view.put("composableWith", capability.composableWith());
        view.put("fallbackCapabilityNames", capability.fallbackCapabilityNames());
        view.put("skillRefs", capability.skillRefs());
        view.put("capabilityType", capability.capabilityType());
        view.put("inputSchema", jsonValue(capability.inputSchema()));
        view.put("outputSchema", jsonValue(capability.outputSchema()));
        view.put("preconditions", capability.preconditions());
        view.put("postconditions", capability.postconditions());
        return view;
    }

    private Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof java.time.Instant instant) {
            return instant.toString();
        }
        if (value instanceof TaskGraph taskGraph) {
            return taskGraphView(taskGraph);
        }
        if (value instanceof TaskNode taskNode) {
            return taskNodeView(taskNode);
        }
        if (value instanceof Observation observation) {
            return observationView(observation);
        }
        if (value instanceof VisibleObject visibleObject) {
            return visibleObjectView(visibleObject);
        }
        if (value instanceof ObservationEvaluation evaluation) {
            return evaluationView(evaluation);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), jsonValue(item)));
            return converted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> converted = new ArrayList<>();
            iterable.forEach(item -> converted.add(jsonValue(item)));
            return converted;
        }
        return String.valueOf(value);
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
        metadata.put("decision", jsonValue(feedbackDecision(action, capabilities, status)));
        metadata.put("state", jsonValue(session.state()));
        metadata.put("visibleObjects", List.copyOf(session.visibleObjects().keySet()));
        metadata.put("pendingActions", session.activePendingActionIds());
        metadata.put("harness", Map.of("runId", runId, "status", status, "mode", "human_feedback",
                "observationCount", observations.size()));
        metadata.put("observations", observations.stream().map(this::observationView).toList());
        metadata.put("riskAssessments", riskAssessments.stream().map(this::riskAssessmentView).toList());
        metadata.put("harnessTrace", trace.stream().map(this::traceView).toList());
        metadata.put("capabilities", capabilities.stream().map(this::capabilityView).toList());
        metadata.put("middleware", middlewareChain.names());
        metadata.put("workspaceRefs", jsonValue(session.state().getOrDefault("workspace_refs", Map.of())));
        metadata.put("workspace", workspace.list(request.conversationId(), "/").stream().map(this::workspaceEntryView)
                .toList());
        return new MarketingResponse(request.conversationId(), answer, List.of(), List.of(), metadata);
    }

    private CapabilityDescriptor capabilityForPendingAction(PendingAction action,
                                                            List<CapabilityDescriptor> capabilities) {
        String explicit = capabilityName(action, capabilities);
        if (!explicit.isBlank()) {
            return capabilityRegistry.find(explicit).orElse(null);
        }
        return null;
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
        return "";
    }

    private String capabilityProvider(PendingAction action, List<CapabilityDescriptor> capabilities) {
        String capabilityName = capabilityName(action, capabilities);
        return capabilityRegistry.find(capabilityName).map(CapabilityDescriptor::provider).orElse("");
    }

    private Map<String, Object> feedbackInputs(PendingAction action, HumanFeedback feedback) {
        Map<String, Object> inputs = new LinkedHashMap<>(action.payload());
        feedback.editedPayload().forEach((key, value) -> {
            if (!Set.of("task_graph_id", "task_node_id", "capability_name", "idempotency_key",
                    "approval_source").contains(key)) {
                inputs.put(key, value);
            }
        });
        inputs.put("confirmed", true);
        inputs.put("pending_action_approved", true);
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

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? fallback : second;
    }

    private record NodeExecutionPlan(TaskNode node, CapabilityDescriptor capability) {
    }

    private record NodeExecutionResult(TaskNode node, CapabilityDescriptor capability, Observation observation) {
    }
}
