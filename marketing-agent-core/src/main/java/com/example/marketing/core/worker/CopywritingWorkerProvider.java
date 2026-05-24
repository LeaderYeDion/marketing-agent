package com.example.marketing.core.worker;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.observation.Observation;

@Service
public class CopywritingWorkerProvider implements WorkerProvider {
    @Override
    public String providerName() {
        return "copywriting_provider";
    }

    @Override
    public boolean supports(WorkerDescriptor descriptor) {
        return descriptor != null && ("copywriting".equals(descriptor.name())
                || "notification_copywriting".equals(descriptor.name()));
    }

    @Override
    public Observation execute(WorkerExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        Map<String, Object> inputs = executionRequest.inputs();
        String product = firstNonBlank(value(inputs.get("product")), value(marketingRequest.product()), "product");
        String channel = firstNonBlank(value(inputs.get("channel")), value(marketingRequest.channel()), "channel");
        String audience = firstNonBlank(value(inputs.get("audience")), value(marketingRequest.audience()), "audience");
        String observations = firstNonBlank(value(inputs.get("source_observations")), "No prior observations.");
        String draft = "Copy for " + channel + " about " + product + ": focus on " + audience
                + ". Available evidence: " + observations;
        ConversationMessage message = ConversationMessage.assistant(draft, providerName(), "final_answer",
                Map.of("worker", executionRequest.worker().name(), "product", product, "channel", channel));
        return new Observation(
                null,
                executionRequest.runId(),
                executionRequest.taskNodeId(),
                executionRequest.worker().name(),
                "succeeded",
                draft,
                Map.of("source_observations", observations),
                Map.of("draft_type", channel),
                0.7,
                List.of(),
                "low",
                false,
                null,
                "",
                false,
                List.of(),
                List.of(message),
                Map.of("current_task", Map.of("type", executionRequest.worker().name(), "status", "succeeded"))
        );
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

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}

