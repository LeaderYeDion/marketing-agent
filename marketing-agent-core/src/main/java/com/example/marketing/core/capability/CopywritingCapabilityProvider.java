package com.example.marketing.core.capability;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.observation.Observation;

@Service
public class CopywritingCapabilityProvider implements CapabilityProvider {
    @Override
    public String providerName() {
        return "copywriting_provider";
    }

    @Override
    public boolean supports(CapabilityDescriptor descriptor) {
        return descriptor != null && ("copywriting".equals(descriptor.name())
                || "notification_copywriting".equals(descriptor.name()));
    }

    @Override
    public Observation execute(CapabilityExecutionRequest executionRequest, MarketingRequest marketingRequest) {
        Map<String, Object> inputs = executionRequest.inputs();
        String product = firstNonBlank(value(inputs.get("product")), value(marketingRequest.product()), "相关商品");
        String channel = firstNonBlank(value(inputs.get("channel")), value(marketingRequest.channel()), "社群");
        String audience = firstNonBlank(value(inputs.get("audience")), value(marketingRequest.audience()), "目标用户");
        String observations = firstNonBlank(value(inputs.get("source_observations")), "以上规则和表格检查结果");
        String draft = """
                社群通知草稿：
                %s 的小伙伴可以关注本次活动。我们已经结合 %s 完成了规则与素材检查，适合的商品会按活动要求参与优惠。
                如果你正在考虑下单，可以优先查看活动商品、优惠条件和有效时间，确认满足条件后再参与。
                """.formatted(audience, observations).trim();
        ConversationMessage message = ConversationMessage.assistant(draft, providerName(), "final_answer",
                Map.of("capability", executionRequest.capability().name(), "product", product, "channel", channel));
        return new Observation(
                null,
                executionRequest.runId(),
                executionRequest.taskNodeId(),
                executionRequest.capability().name(),
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
                Map.of("current_task", Map.of("type", executionRequest.capability().name(), "status", "succeeded"))
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
