package com.example.marketing.core.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.capability.CapabilityDescriptor;

@Service
public class ObservationContractValidator {
    public List<String> validate(CapabilityDescriptor capability, Observation observation) {
        List<String> errors = new ArrayList<>();
        if (capability == null || observation == null) {
            errors.add("OBSERVATION_CONTRACT_INPUT_NULL");
            return errors;
        }
        if (!capability.name().equals(observation.capabilityName())) {
            errors.add("OBSERVATION_CAPABILITY_MISMATCH:" + capability.name() + ":" + observation.capabilityName());
        }
        if (requiresGrounding(capability) && observation.evidence().isEmpty()
                && observation.artifacts().isEmpty() && observation.visibleObjects().isEmpty()) {
            errors.add("OBSERVATION_MISSING_GROUNDING:" + observation.id());
        }
        Object required = capability.outputSchema().get("required");
        if (required instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String field = String.valueOf(item);
                if (!hasOutputField(observation, field)) {
                    errors.add("OBSERVATION_OUTPUT_FIELD_MISSING:" + observation.id() + ":" + field);
                }
            }
        }
        return errors;
    }

    private boolean hasOutputField(Observation observation, String field) {
        if (field == null || field.isBlank()) {
            return true;
        }
        Map<String, Object> evidence = observation.evidence();
        Map<String, Object> artifacts = observation.artifacts();
        return evidence.containsKey(field) || artifacts.containsKey(field)
                || (observation.summary() != null && observation.summary().contains(field));
    }

    private boolean requiresGrounding(CapabilityDescriptor capability) {
        String type = capability.capabilityType().toLowerCase(java.util.Locale.ROOT);
        return type.contains("query") || type.contains("delegation") || capability.outputContract().stream()
                .anyMatch(field -> field.contains("evidence") || field.contains("citation")
                        || field.contains("rule") || field.contains("summary"));
    }
}
