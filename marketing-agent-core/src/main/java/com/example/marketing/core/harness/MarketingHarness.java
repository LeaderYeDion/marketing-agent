package com.example.marketing.core.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.api.MarketingResponse;
import com.example.marketing.core.agent.AgentOrchestrator;
import com.example.marketing.core.agent.MainAgentDecision;
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
    private final AgentOrchestrator legacyOrchestrator;

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
                            AgentOrchestrator legacyOrchestrator) {
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
        this.legacyOrchestrator = legacyOrchestrator;
    }

    public MarketingResponse run(MarketingRequest request) {
        if (HumanFeedback.fromVariables(request.variables()).isPresent()) {
            MarketingResponse response = legacyOrchestrator.run(request);
            return withLegacyHarnessMetadata(response);
        }
        return conversationLockManager.withConversationLock(request.conversationId(), () -> runLocked(request));
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

        graph = graph.withStatus("running");
        for (TaskNode node : graph.nodes()) {
            CapabilityDescriptor capability = capabilityRegistry.find(node.capabilityName()).orElse(null);
            if (capability == null) {
                Observation failed = Observation.failed(runId, node.id(), node.capabilityName(),
                        "Capability is not registered: " + node.capabilityName(), "CAPABILITY_NOT_FOUND", false);
                observations.add(failed);
                graph = graph.withNode(node.withObservation(TaskNodeStatus.FAILED, failed.id()));
                trace(trace, runId, "capability_missing", node.capabilityName(), "failed",
                        Map.of("taskNodeId", node.id()));
                break;
            }

            Map<String, Object> nodeInputs = enrichInputs(node.inputs(), observations);
            TaskNode runningNode = node.withInputs(nodeInputs).withStatus(TaskNodeStatus.RUNNING);
            RiskAssessment risk = riskPolicyEngine.assess(capability, runningNode, nodeInputs);
            riskAssessments.add(risk);
            runningNode = runningNode.withRisk(risk.riskLevel());
            graph = graph.withNode(runningNode);
            trace(trace, runId, "capability_selected", capability.name(), "running",
                    Map.of("taskNodeId", node.id(), "risk", risk.riskLevel(), "requiresApproval",
                            risk.requiresApproval()));

            Observation observation = executeCapability(runId, graph, runningNode, capability, context, request,
                    session);
            observations.add(observation);
            commitObservation(session, observation);
            TaskNodeStatus status = statusFor(observation);
            graph = graph.withNode(runningNode.withObservation(status, observation.id()));
            trace(trace, runId, "observation_recorded", capability.name(), observation.status(),
                    Map.of("taskNodeId", node.id(), "observationId", observation.id()));

            RecoveryDecision recovery = recoveryPolicyEngine.decide(capability, observation);
            trace(trace, runId, "recovery_evaluated", capability.name(), recovery.action(),
                    Map.of("reason", recovery.reason(), "fallback", recovery.fallbackCapability()));
            if (TaskNodeStatus.WAITING_FOR_APPROVAL.equals(status)
                    || TaskNodeStatus.WAITING_FOR_USER.equals(status)
                    || TaskNodeStatus.FAILED.equals(status)) {
                break;
            }
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
                metadata(request, session, graph, observations, riskAssessments, trace, capabilities));
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

    private void commitObservation(ConversationSession session, Observation observation) {
        session.addHandoffSummary(ContextSummary.of("capability", observation.id(), observation.status(),
                observation.summary(), Map.of("taskNodeId", observation.taskNodeId(),
                        "capability", observation.capabilityName())));
        observation.messagesToCommit().forEach(session::addMessage);
        observation.visibleObjects().forEach(object -> {
            session.addVisibleObject(object);
            if ("hitl_confirmation".equals(object.type()) && "pending".equals(object.status())) {
                Map<String, Object> pending = new LinkedHashMap<>(object.data());
                pending.put("visible_object_id", object.id());
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
        metadata.put("decision", compatibleDecision(request, graph));
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

    private MainAgentDecision compatibleDecision(MarketingRequest request, TaskGraph graph) {
        TaskNode first = graph.nodes().isEmpty() ? null : graph.nodes().getFirst();
        CapabilityDescriptor capability = first == null ? null : capabilityRegistry.find(first.capabilityName())
                .orElse(null);
        String skillName = first == null ? "" : first.capabilityName();
        String delegateTo = capability == null ? "" : capability.provider();
        List<String> candidateSkills = graph.nodes().stream().map(TaskNode::capabilityName).distinct().toList();
        return new MainAgentDecision("delegate", skillName, candidateSkills, delegateTo, "", first == null
                ? Map.of("question", request.query() == null ? "" : request.query())
                : first.inputs(), graph.userGoal());
    }

    private void trace(List<HarnessTraceEvent> trace, String runId, String eventType, String source, String status,
                       Map<String, Object> data) {
        trace.add(HarnessTraceEvent.of(runId, eventType, source, status, data));
    }

    private MarketingResponse withLegacyHarnessMetadata(MarketingResponse response) {
        Map<String, Object> metadata = new LinkedHashMap<>(response.metadata());
        metadata.put("harness", Map.of("status", "delegated_to_legacy_feedback"));
        return new MarketingResponse(response.conversationId(), response.answer(), response.suggestions(),
                response.retrievedDocuments(), metadata);
    }
}
