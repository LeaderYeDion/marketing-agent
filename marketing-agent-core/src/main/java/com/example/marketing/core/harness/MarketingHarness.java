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
import com.example.marketing.core.worker.WorkerDescriptor;
import com.example.marketing.core.worker.WorkerExecutionRequest;
import com.example.marketing.core.worker.WorkerProvider;
import com.example.marketing.core.worker.WorkerRegistry;
import com.example.marketing.core.memory.ContextAssembler;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.middleware.WorkerCallDecision;
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
    private final WorkerRegistry workerRegistry;
    private final List<WorkerProvider> workerProviders;
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
                            WorkerRegistry workerRegistry,
                            List<WorkerProvider> workerProviders,
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
        this.workerRegistry = workerRegistry;
        this.workerProviders = workerProviders == null ? List.of() : List.copyOf(workerProviders);
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
        List<WorkerDescriptor> workers = workerRegistry.list();
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
                    "No pending action was found for this feedback. Please start a new request.",
                    observations, riskAssessments, trace, workers, null);
        }
        if (!action.isPending()) {
            return finishFeedback(request, session, runId, "feedback_not_pending",
                    "This action is currently " + action.status() + " and cannot be handled again.",
                    observations, riskAssessments, trace, workers, action);
        }
        if (action.isExpired()) {
            PendingAction expired = pendingActionStateMachine.expire(action, request.userId());
            session.updatePendingAction(expired);
            session.updateVisibleObjectStatus(action.visibleObjectId(), "expired");
            auditEventPublisher.publish(AuditEvent.of("pending_action_expired", request.conversationId(), "harness",
                    pendingActionAuditData(expired)));
            return finishFeedback(request, session, runId, "feedback_expired",
                    "This pending action has expired. Please start a new request.",
                    observations, riskAssessments, trace, workers, expired);
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
                    "The pending action has been rejected.",
                    observations, riskAssessments, trace, workers, rejected);
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
                    "The pending action has been edited and is waiting for a new run.", observations, riskAssessments, trace, workers, edited);
        }

        PendingAction approved = pendingActionStateMachine.approve(action, request.userId());
        session.updatePendingAction(approved);
        session.updateVisibleObjectStatus(action.visibleObjectId(), "approved");
        auditEventPublisher.publish(AuditEvent.of("hitl_approved", request.conversationId(), "harness",
                Map.of("pendingActionId", action.id(), "sourceAgent", action.sourceAgent())));
        auditEventPublisher.publish(AuditEvent.of("pending_action_approved", request.conversationId(), "harness",
                pendingActionAuditData(approved)));

        WorkerDescriptor worker = workerForPendingAction(approved, workers);
        if (worker == null) {
            return finishFeedback(request, session, runId, "feedback_worker_missing",
                    "The approved pending action no longer maps to a registered worker.", observations, riskAssessments, trace, workers, approved);
        }
        Map<String, Object> inputs = feedbackInputs(approved, feedback);
        TaskGraph graph = graphForFeedback(session, approved);
        TaskNode node = nodeForFeedback(graph, approved, worker, inputs).withStatus(TaskNodeStatus.RUNNING);
        WorkerCallDecision permission = middlewareChain.beforeWorkerCall(invocation,
                graph == null ? new TaskGraph("tg_feedback_permission", "feedback", List.of(node), "running") : graph,
                node, worker);
        RiskAssessment risk = permission.riskAssessment() == null
                ? new RiskAssessment(worker.name(), worker.riskLevel(), false, true,
                "No middleware risk assessment returned.")
                : permission.riskAssessment();
        riskAssessments.add(risk);
        node = node.withRisk(risk.riskLevel());
        graph = graph == null
                ? new TaskGraph("tg_feedback_" + UUID.randomUUID().toString().substring(0, 8),
                "Resume pending action " + approved.id(), List.of(node), "running")
                : graph.withNode(node).withStatus("running");
        HarnessContext context = assembleContext(invocation, workers);
        Observation observation = executeWorker(runId, graph, node, worker, context, request, session);
        middlewareChain.afterWorkerCall(invocation, graph, node, worker, observation);
        observation = commitObservation(invocation, observation, graph);
        observations.add(observation);
        ObservationEvaluation evaluation = observationEvaluator.evaluate(worker, node, observation);
        evaluations.add(evaluation);
        TaskNodeStatus status = statusFor(observation);
        TaskNode completedNode = node.withObservation(status, observation.id());
        graph = graph.withNode(completedNode);
        trace(trace, runId, "feedback_worker_executed", worker.name(), observation.status(),
                Map.of("pendingActionId", approved.id(), "observationId", observation.id()));
        if (TaskNodeStatus.SUCCEEDED.equals(status)) {
            PendingAction current = session.pendingActions().getOrDefault(approved.id(), approved);
            PendingAction executed = pendingActionStateMachine.executed(current, request.userId());
            session.updatePendingAction(executed);
            session.updateVisibleObjectStatus(approved.visibleObjectId(), "executed");
            auditEventPublisher.publish(AuditEvent.of("pending_action_executed", request.conversationId(),
                    "harness", pendingActionAuditData(executed, observation)));
        }
        RecoveryDecision recovery = recoveryPolicyEngine.decide(worker, completedNode, observation);
        trace(trace, runId, "feedback_recovery_evaluated", worker.name(), recovery.action(),
                Map.of("reason", recovery.reason(), "fallback", recovery.fallbackWorker(),
                        "evaluation", evaluation));
        graph = applyRecoveryIfNeeded(graph, completedNode, worker, observation, recovery, observations, trace,
                runId, request, context);
        if (graph.hasPendingNodes() && !graph.isPausedOrTerminal()) {
            HarnessContext resumedContext = assembleContext(invocation, workers);
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
                metadata(request, session, graph, observations, evaluations, riskAssessments, trace, workers,
                        null));
    }

    private MarketingResponse runLocked(MarketingRequest request) {
        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = conversationStore.getOrCreate(request.conversationId());
        session.addMessage(ConversationMessage.user(request.query() == null ? "" : request.query(),
                Map.of("variables", request.variables() == null ? Map.of() : request.variables())));

        List<WorkerDescriptor> workers = workerRegistry.list();
        List<HarnessTraceEvent> trace = new ArrayList<>();
        HarnessInvocationContext invocation = new HarnessInvocationContext(runId, request, session, trace);
        HarnessContext context = assembleContext(invocation, workers);
        List<Observation> observations = new ArrayList<>();
        List<ObservationEvaluation> evaluations = new ArrayList<>();
        List<RiskAssessment> riskAssessments = new ArrayList<>();

        TaskGraph resumed = resumeWaitingGraphIfPossible(request, session, workers, trace, runId);
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
                            workers, null));
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
                metadata(request, session, graph, observations, evaluations, riskAssessments, trace, workers,
                        validation));
    }

    private HarnessContext assembleContext(HarnessInvocationContext invocation,
                                           List<WorkerDescriptor> workers) {
        middlewareChain.beforeContextAssemble(invocation);
        HarnessContext context = contextAssembler.assemble(invocation.request(), invocation.session(), workers);
        middlewareChain.afterContextAssemble(invocation, context);
        return context;
    }

    private TaskGraph resumeWaitingGraphIfPossible(MarketingRequest request, ConversationSession session,
                                                   List<WorkerDescriptor> workers,
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
        WorkerDescriptor worker = workerRegistry.find(waitingNode.workerName()).orElse(null);
        if (worker == null) {
            return null;
        }
        Map<String, Object> mergedInputs = new LinkedHashMap<>(waitingNode.inputs());
        if (request.variables() != null) {
            mergedInputs.putAll(request.variables());
        }
        List<String> missing = missingRequiredInputs(worker, mergedInputs);
        if (missing.size() == 1 && request.query() != null && !request.query().isBlank()
                && !mergedInputs.containsKey(missing.getFirst())) {
            mergedInputs.put(missing.getFirst(), request.query());
            missing = missingRequiredInputs(worker, mergedInputs);
        }
        if (!missing.isEmpty()) {
            trace(trace, runId, "waiting_graph_still_missing_inputs", worker.name(), "waiting_for_user",
                    Map.of("taskGraphId", graph.id(), "taskNodeId", waitingNode.id(), "missingInputs", missing));
            return null;
        }
        TaskNode resumedNode = waitingNode.withInputs(mergedInputs).withStatus(TaskNodeStatus.PENDING);
        TaskGraph resumed = graph.withNode(resumedNode).withStatus("running");
        session.state().remove("waiting_node");
        trace(trace, runId, "waiting_graph_resumed", worker.name(), "running",
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

    private TaskNode nodeForFeedback(TaskGraph graph, PendingAction action, WorkerDescriptor worker,
                                     Map<String, Object> inputs) {
        String taskNodeId = stringValue(action.payload().get("task_node_id"));
        if (graph != null && !taskNodeId.isBlank()) {
            TaskNode plannedNode = graph.findNode(taskNodeId).orElse(null);
            if (plannedNode != null) {
                return plannedNode.withInputs(inputs);
            }
        }
        String nodeId = taskNodeId.isBlank() ? "feedback_node_1" : taskNodeId;
        return TaskNode.pending(nodeId, "Resume approved action " + action.id(), worker.name(),
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
                WorkerDescriptor worker = workerRegistry.find(node.workerName()).orElse(null);
                if (worker == null) {
                    Observation failed = Observation.failed(runId, node.id(), node.workerName(),
                            "Worker is not registered: " + node.workerName(), "CAPABILITY_NOT_FOUND", false);
                    observations.add(failed);
                    current = current.withNode(node.withObservation(TaskNodeStatus.FAILED, failed.id()));
                    trace(trace, runId, "worker_missing", node.workerName(), "failed",
                            Map.of("taskNodeId", node.id()));
                    continue;
                }
                Map<String, Object> nodeInputs = enrichInputs(node.inputs(), dependencyObservations(node,
                        observations));
                TaskNode runningNode = node.withInputs(nodeInputs).withStatus(TaskNodeStatus.RUNNING);
                List<String> missingInputs = missingRequiredInputs(worker, nodeInputs);
                if (!missingInputs.isEmpty()) {
                    Observation waiting = waitingForUserObservation(runId, current, runningNode, worker,
                            missingInputs);
                    waiting = commitObservation(invocation, waiting, current);
                    observations.add(waiting);
                    TaskNode waitingNode = runningNode.withObservation(TaskNodeStatus.WAITING_FOR_USER, waiting.id());
                    current = current.withNode(waitingNode);
                    trace(trace, runId, "node_waiting_for_user", worker.name(), waiting.status(),
                            Map.of("taskNodeId", node.id(), "missingInputs", missingInputs,
                                    "observationId", waiting.id()));
                    continue;
                }
                WorkerCallDecision permission = middlewareChain.beforeWorkerCall(invocation, current,
                        runningNode, worker);
                RiskAssessment risk = permission.riskAssessment() == null
                        ? new RiskAssessment(worker.name(), worker.riskLevel(), false, true,
                        "No middleware risk assessment returned.")
                        : permission.riskAssessment();
                riskAssessments.add(risk);
                runningNode = runningNode.withRisk(risk.riskLevel());
                current = current.withNode(runningNode);
                if (risk.requiresApproval()) {
                    Observation waiting = waitingForApprovalObservation(runId, current, runningNode, worker, risk);
                    middlewareChain.onHumanApprovalRequired(invocation, current, runningNode, worker, waiting);
                    waiting = commitObservation(invocation, waiting, current);
                    observations.add(waiting);
                    TaskNode waitingNode = runningNode.withObservation(TaskNodeStatus.WAITING_FOR_APPROVAL,
                            waiting.id());
                    current = current.withNode(waitingNode);
                    trace(trace, runId, "hitl_boundary_enforced", worker.name(), waiting.status(),
                            Map.of("taskNodeId", node.id(), "risk", risk.riskLevel(),
                                    "observationId", waiting.id()));
                    continue;
                }
                executionPlans.add(new NodeExecutionPlan(runningNode, worker));
                trace(trace, runId, "worker_selected", worker.name(), "running",
                        Map.of("taskNodeId", node.id(), "risk", risk.riskLevel(), "requiresApproval",
                                risk.requiresApproval(), "dependsOn", node.dependsOn()));
            }

            TaskGraph batchGraph = current;
            List<CompletableFuture<NodeExecutionResult>> futures = executionPlans.stream()
                    .map(plan -> CompletableFuture.supplyAsync(() -> new NodeExecutionResult(plan.node(),
                            plan.worker(), executeWorker(runId, batchGraph, plan.node(), plan.worker(),
                            context, request, session))))
                    .toList();

            for (CompletableFuture<NodeExecutionResult> future : futures) {
                NodeExecutionResult result = future.join();
                Observation observation = result.observation();
                middlewareChain.afterWorkerCall(invocation, current, result.node(), result.worker(),
                        observation);
                observation = commitObservation(invocation, observation, current);
                observations.add(observation);
                ObservationEvaluation evaluation = observationEvaluator.evaluate(result.worker(), result.node(),
                        observation);
                evaluations.add(evaluation);
                TaskNodeStatus status = statusFor(observation);
                TaskNode completedNode = result.node().withObservation(status, observation.id());
                current = current.withNode(completedNode);
                trace(trace, runId, "observation_recorded", result.worker().name(), observation.status(),
                        Map.of("taskNodeId", result.node().id(), "observationId", observation.id()));

                RecoveryDecision recovery = recoveryPolicyEngine.decide(result.worker(), completedNode,
                        observation);
                trace(trace, runId, "recovery_evaluated", result.worker().name(), recovery.action(),
                        Map.of("reason", recovery.reason(), "fallback", recovery.fallbackWorker(),
                                "evaluation", evaluation));
                current = applyRecoveryIfNeeded(current, completedNode, result.worker(), observation, recovery,
                        observations, trace, runId, request, context);
            }
        }
        return current;
    }

    private Observation waitingForUserObservation(String runId, TaskGraph graph, TaskNode node,
                                                  WorkerDescriptor worker, List<String> missingInputs) {
        return new Observation(null, runId, node.id(), worker.name(), "waiting_for_user",
                "Missing required inputs for worker " + worker.name() + ": " + String.join(", ", missingInputs),
                Map.of("expected_schema", worker.inputSchema(), "task_graph_id", graph.id(),
                        "task_node_id", node.id()),
                Map.of(), 0.2, missingInputs, "low", false, null, "MISSING_INPUTS", false,
                List.of(),
                List.of(ConversationMessage.assistant("Missing required inputs: " + String.join(", ", missingInputs),
                        "harness", "waiting_for_user",
                        Map.of("taskGraphId", graph.id(), "taskNodeId", node.id(),
                                "missingInputs", missingInputs))),
                Map.of("waiting_node", Map.of(
                        "task_graph_id", graph.id(),
                        "task_node_id", node.id(),
                        "worker_name", worker.name(),
                        "missing_inputs", missingInputs,
                        "expected_schema", worker.inputSchema()
                )));
    }

    private Observation waitingForApprovalObservation(String runId, TaskGraph graph, TaskNode node,
                                                      WorkerDescriptor worker, RiskAssessment risk) {
        String cardId = "confirm_" + node.id() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "pending_action:" + graph.id() + ":" + node.id() + ":" + worker.name();
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("worker_name", worker.name());
        diff.put("goal", node.goal());
        diff.put("inputs", node.inputs());
        diff.put("side_effects", worker.sideEffects());
        diff.put("permissions", worker.permissions());
        Map<String, Object> payload = new LinkedHashMap<>(node.inputs());
        payload.put("source_agent", worker.provider().isBlank() ? "harness" : worker.provider());
        payload.put("worker_name", worker.name());
        payload.put("task_graph_id", graph.id());
        payload.put("task_node_id", node.id());
        payload.put("idempotency_key", idempotencyKey);
        payload.put("risk_level", worker.riskLevel());
        payload.put("approval_source", "harness_risk_policy");
        payload.put("execution_diff", diff);
        payload.put("actions", List.of(
                Map.of("id", "confirm", "label", "Confirm", "enabled", true),
                Map.of("id", "cancel", "label", "Cancel", "enabled", true),
                Map.of("id", "modify", "label", "Modify", "enabled", true)
        ));
        String summary = "Approval is required before executing worker: " + node.goal();
        VisibleObject card = VisibleObject.of(cardId, "hitl_confirmation", "Approve " + worker.name(),
                "pending", summary, payload);
        ActionProposal proposal = new ActionProposal(cardId, "hitl_confirmation", worker.name(), summary,
                payload, worker.riskLevel());
        return new Observation(
                null,
                runId,
                node.id(),
                worker.name(),
                "waiting_for_approval",
                summary,
                Map.of("risk_reason", risk.reason(), "harness_boundary", "provider_not_invoked_before_approval"),
                Map.of("idempotency_key", idempotencyKey, "execution_diff", diff),
                0.9,
                List.of(),
                worker.riskLevel(),
                true,
                proposal,
                "",
                false,
                List.of(card),
                List.of(ConversationMessage.assistant(summary, "harness", "waiting_for_approval",
                        Map.of("pendingActionId", cardId, "worker", worker.name()))),
                Map.of("current_task", Map.of("type", worker.name(), "status", "waiting_for_approval",
                        "task_graph_id", graph.id(), "task_node_id", node.id()))
        );
    }

    private TaskGraph applyRecoveryIfNeeded(TaskGraph graph, TaskNode node, WorkerDescriptor worker,
                                            Observation observation, RecoveryDecision recovery,
                                            List<Observation> observations, List<HarnessTraceEvent> trace,
                                            String runId, MarketingRequest request, HarnessContext context) {
        if (!TaskNodeStatus.FAILED.equals(statusFor(observation))) {
            return graph;
        }
        if (observation.retryable() && node.retryCount() < 1) {
            TaskNode retryNode = node.incrementRetry().withStatus(TaskNodeStatus.PENDING);
            trace(trace, runId, "node_retry_scheduled", worker.name(), "pending",
                    Map.of("taskNodeId", node.id(), "retryCount", retryNode.retryCount()));
            return graph.withNode(retryNode);
        }
        if ("fallback".equals(recovery.action()) && !recovery.fallbackWorker().isBlank()
                && workerRegistry.find(recovery.fallbackWorker()).isPresent()) {
            TaskNode skippedOriginal = node.withObservation(TaskNodeStatus.SKIPPED, observation.id());
            String fallbackNodeId = uniqueNodeId(graph, node.id() + "_fallback");
            TaskNode fallbackNode = TaskNode.planned(
                    fallbackNodeId,
                    "Recover from failed worker " + worker.name(),
                    recovery.fallbackWorker(),
                    Map.of("question", observation.summary(), "source_observations", observation.summary(),
                            "recovery_reason", recovery.reason()),
                    node.dependsOn(),
                    "Fallback worker produced a usable observation or clarification.",
                    node.priority() + 1,
                    recovery.reason()
            );
            trace(trace, runId, "fallback_node_scheduled", recovery.fallbackWorker(), "pending",
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

    private Observation executeWorker(String runId, TaskGraph graph, TaskNode node, WorkerDescriptor worker,
                                          HarnessContext context, MarketingRequest request,
                                          ConversationSession session) {
        WorkerProvider provider = workerProviders.stream()
                .filter(candidate -> candidate.supports(worker))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            return Observation.failed(runId, node.id(), worker.name(),
                    "No provider supports worker: " + worker.name(), "PROVIDER_NOT_FOUND", false);
        }
        try {
            WorkerExecutionRequest executionRequest = new WorkerExecutionRequest(
                    runId,
                    graph.id(),
                    node.id(),
                    request.conversationId(),
                    request.query(),
                    worker,
                    node.inputs(),
                    context.compressedContext(),
                    context.memory().workspaceRefs(),
                    List.copyOf(session.visibleObjects().values())
            );
            return provider.execute(executionRequest, request);
        }
        catch (RuntimeException ex) {
            return Observation.failed(runId, node.id(), worker.name(),
                    ex.getMessage() == null ? "Worker execution failed." : ex.getMessage(),
                    ex.getClass().getSimpleName(), true);
        }
    }

    private Observation commitObservation(HarnessInvocationContext invocation, Observation rawObservation,
                                         TaskGraph graph) {
        Observation observation = middlewareChain.beforeObservationCommit(invocation, graph, rawObservation);
        ConversationSession session = invocation.session();
        String workspacePath = workspacePathFor(observation);
        session.addHandoffSummary(ContextSummary.of("worker", observation.id(), observation.status(),
                observation.summary(), Map.of("taskNodeId", observation.taskNodeId(),
                        "worker", observation.workerName(),
                        "workspace_path", workspacePath)));
        observation.messagesToCommit().forEach(session::addMessage);
        observation.visibleObjects().forEach(object -> {
            session.addVisibleObject(object);
            if ("hitl_confirmation".equals(object.type()) && "pending".equals(object.status())) {
                Map<String, Object> pending = new LinkedHashMap<>(object.data());
                pending.put("visible_object_id", object.id());
                pending.putIfAbsent("task_graph_id", graph == null ? "" : graph.id());
                pending.putIfAbsent("task_node_id", observation.taskNodeId());
                pending.putIfAbsent("worker_name", observation.workerName());
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
        observationRef.put("worker_name", observation.workerName());
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
                "latest_worker_name", observation.workerName()));
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
        String workerName = firstNonBlank(stringValue(payload.get("worker_name")),
                stringValue(payload.get("skill_name")), observation.workerName());
        if (workerName.isBlank() || workerRegistry.find(workerName).isEmpty()) {
            throw new IllegalStateException("Pending action is missing a registered worker binding: "
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
                "pending_action:" + graphId + ":" + taskNodeId + ":" + workerName);
        WorkerDescriptor worker = workerRegistry.find(workerName)
                .orElseThrow(() -> new IllegalStateException("Unknown pending action worker: " + workerName));
        payload.put("visible_object_id", object.id());
        payload.put("task_graph_id", graphId);
        payload.put("task_node_id", taskNodeId);
        payload.put("worker_name", workerName);
        payload.put("idempotency_key", idempotencyKey);
        payload.putIfAbsent("approval_source", "harness_pending_action_builder");
        payload.put("source_observation_id", observation.id());
        payload.put("source_task_node_id", observation.taskNodeId());
        payload.put("source_worker_name", observation.workerName());
        payload.put("source_provider", worker.provider());
        String sourceAgent = firstNonBlank(stringValue(payload.get("source_agent")), worker.provider(), "harness");
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
        data.put("workerName", stringValue(action.payload().get("worker_name")));
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

    private List<String> missingRequiredInputs(WorkerDescriptor worker, Map<String, Object> inputs) {
        if (worker.requiredInputs().isEmpty()) {
            return List.of();
        }
        return worker.requiredInputs().stream()
                .filter(input -> !approvalRuntimeInput(worker, input))
                .filter(input -> {
                    Object value = inputs == null ? null : inputs.get(input);
                    return value == null || value.toString().isBlank();
                })
                .toList();
    }

    private boolean approvalRuntimeInput(WorkerDescriptor worker, String input) {
        return worker.requiresHumanApproval()
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
            return "No worker produced an observation yet.";
        }
        Observation last = observations.getLast();
        if ("waiting_for_user".equals(last.status())) {
            return "Please provide missing inputs: " + String.join(", ", last.missingInputs());
        }
        if ("waiting_for_approval".equals(last.status())) {
            return last.summary();
        }
        String combined = observations.stream()
                .map(Observation::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n\n" + right);
        return combined.isBlank() ? "The worker completed without a user-visible summary." : combined;
    }

    private Map<String, Object> metadata(MarketingRequest request, ConversationSession session, TaskGraph graph,
                                         List<Observation> observations,
                                         List<ObservationEvaluation> evaluations,
                                         List<RiskAssessment> riskAssessments,
                                         List<HarnessTraceEvent> trace,
                                         List<WorkerDescriptor> workers,
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
        metadata.put("workers", workers.stream().map(this::workerView).toList());
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
        WorkerDescriptor worker = first == null ? null : workerRegistry.find(first.workerName())
                .orElse(null);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", "execute_task_graph");
        decision.put("firstWorker", first == null ? "" : first.workerName());
        decision.put("firstProvider", worker == null ? "" : worker.provider());
        decision.put("workerSequence", graph.nodes().stream().map(TaskNode::workerName).distinct().toList());
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
        view.put("workerName", node.workerName());
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
        view.put("workerName", observation.workerName());
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
        view.put("sourceWorker", proposal.sourceWorker());
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
        view.put("workerName", risk.workerName());
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
        view.put("workerName", evaluation.workerName());
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

    private Map<String, Object> workerView(WorkerDescriptor worker) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", worker.name());
        view.put("description", worker.description());
        view.put("requiredInputs", worker.requiredInputs());
        view.put("outputContract", worker.outputContract());
        view.put("permissions", worker.permissions());
        view.put("sideEffects", worker.sideEffects());
        view.put("requiresHumanApproval", worker.requiresHumanApproval());
        view.put("riskLevel", worker.riskLevel());
        view.put("provider", worker.provider());
        view.put("executionMode", worker.executionMode());
        view.put("composableWith", worker.composableWith());
        view.put("fallbackWorkerNames", worker.fallbackWorkerNames());
        view.put("skillRefs", worker.skillRefs());
        view.put("workerType", worker.workerType());
        view.put("inputSchema", jsonValue(worker.inputSchema()));
        view.put("outputSchema", jsonValue(worker.outputSchema()));
        view.put("preconditions", worker.preconditions());
        view.put("postconditions", worker.postconditions());
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

    private Map<String, Object> feedbackDecision(PendingAction action, List<WorkerDescriptor> workers,
                                                  String status) {
        Map<String, Object> decision = new LinkedHashMap<>();
        String workerName = workerName(action, workers);
        decision.put("action", "human_feedback");
        decision.put("worker", workerName);
        decision.put("workerSequence", workerName.isBlank() ? List.of() : List.of(workerName));
        decision.put("provider", workerProvider(action, workers));
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
                                             List<WorkerDescriptor> workers, PendingAction action) {
        trace(trace, runId, status, "harness", status, action == null ? Map.of() : Map.of("pendingActionId",
                action.id(), "pendingActionStatus", action.status().name()));
        session.addMessage(ConversationMessage.assistant(answer, "harness", status,
                action == null ? Map.of() : Map.of("pendingActionId", action.id())));
        conversationStore.save(session);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", jsonValue(feedbackDecision(action, workers, status)));
        metadata.put("state", jsonValue(session.state()));
        metadata.put("visibleObjects", List.copyOf(session.visibleObjects().keySet()));
        metadata.put("pendingActions", session.activePendingActionIds());
        metadata.put("harness", Map.of("runId", runId, "status", status, "mode", "human_feedback",
                "observationCount", observations.size()));
        metadata.put("observations", observations.stream().map(this::observationView).toList());
        metadata.put("riskAssessments", riskAssessments.stream().map(this::riskAssessmentView).toList());
        metadata.put("harnessTrace", trace.stream().map(this::traceView).toList());
        metadata.put("workers", workers.stream().map(this::workerView).toList());
        metadata.put("middleware", middlewareChain.names());
        metadata.put("workspaceRefs", jsonValue(session.state().getOrDefault("workspace_refs", Map.of())));
        metadata.put("workspace", workspace.list(request.conversationId(), "/").stream().map(this::workspaceEntryView)
                .toList());
        return new MarketingResponse(request.conversationId(), answer, List.of(), List.of(), metadata);
    }

    private WorkerDescriptor workerForPendingAction(PendingAction action,
                                                            List<WorkerDescriptor> workers) {
        String explicit = workerName(action, workers);
        if (!explicit.isBlank()) {
            return workerRegistry.find(explicit).orElse(null);
        }
        return null;
    }

    private String workerName(PendingAction action, List<WorkerDescriptor> workers) {
        if (action == null) {
            return "";
        }
        String workerName = stringValue(action.payload().get("worker_name"));
        if (workerRegistry.find(workerName).isPresent()) {
            return workerName;
        }
        String skillName = stringValue(action.payload().get("skill_name"));
        if (workerRegistry.find(skillName).isPresent()) {
            return skillName;
        }
        return "";
    }

    private String workerProvider(PendingAction action, List<WorkerDescriptor> workers) {
        String workerName = workerName(action, workers);
        return workerRegistry.find(workerName).map(WorkerDescriptor::provider).orElse("");
    }

    private Map<String, Object> feedbackInputs(PendingAction action, HumanFeedback feedback) {
        Map<String, Object> inputs = new LinkedHashMap<>(action.payload());
        feedback.editedPayload().forEach((key, value) -> {
            if (!Set.of("task_graph_id", "task_node_id", "worker_name", "idempotency_key",
                    "approval_source").contains(key)) {
                inputs.put(key, value);
            }
        });
        inputs.put("confirmed", true);
        inputs.put("pending_action_approved", true);
        inputs.put("human_feedback_decision", feedback.decision());
        inputs.put("pending_action_id", action.id());
        inputs.put("visible_object_id", action.visibleObjectId());
        if (!inputs.containsKey("worker_name")) {
            inputs.put("worker_name", stringValue(inputs.get("skill_name")));
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

    private record NodeExecutionPlan(TaskNode node, WorkerDescriptor worker) {
    }

    private record NodeExecutionResult(TaskNode node, WorkerDescriptor worker, Observation observation) {
    }
}

