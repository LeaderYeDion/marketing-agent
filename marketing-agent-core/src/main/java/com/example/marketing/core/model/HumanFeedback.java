package com.example.marketing.core.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record HumanFeedback(
        String pendingActionId,
        String visibleObjectId,
        String decision,
        Map<String, Object> editedPayload
) {
    public static Optional<HumanFeedback> fromVariables(Map<String, Object> variables) {
        if (variables == null) {
            return Optional.empty();
        }
        Object raw = variables.get("human_feedback");
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        String pendingActionId = stringValue(map.get("pending_action_id"));
        String visibleObjectId = stringValue(map.get("visible_object_id"));
        String decision = normalizeDecision(stringValue(map.get("decision")));
        Map<String, Object> editedPayload = map.get("edited_payload") instanceof Map<?, ?> edited
                ? copyStringKeyMap(edited)
                : Map.of();
        if (pendingActionId.isBlank() && visibleObjectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new HumanFeedback(pendingActionId, visibleObjectId, decision, editedPayload));
    }

    public boolean isApprove() {
        return "approve".equals(decision);
    }

    public boolean isReject() {
        return "reject".equals(decision);
    }

    public boolean isEdit() {
        return "edit".equals(decision);
    }

    private static String normalizeDecision(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if ("confirm".equals(normalized) || "confirmed".equals(normalized) || "yes".equals(normalized)) {
            return "approve";
        }
        if ("cancel".equals(normalized) || "deny".equals(normalized) || "no".equals(normalized)) {
            return "reject";
        }
        return normalized.isBlank() ? "approve" : normalized;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(key.toString(), value);
            }
        });
        return Map.copyOf(copy);
    }
}
