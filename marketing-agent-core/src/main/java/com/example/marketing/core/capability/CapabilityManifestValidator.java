package com.example.marketing.core.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class CapabilityManifestValidator {
    public List<String> validate(List<CapabilityManifestDescriptor> manifests, List<CapabilityProvider> providers) {
        List<String> errors = new ArrayList<>();
        Set<String> names = manifests == null ? Set.of() : manifests.stream()
                .map(CapabilityManifestDescriptor::name)
                .collect(Collectors.toSet());
        Set<String> providerNames = providers == null ? Set.of() : providers.stream()
                .map(CapabilityProvider::providerName)
                .collect(Collectors.toSet());
        for (CapabilityManifestDescriptor manifest : manifests == null ? List.<CapabilityManifestDescriptor>of()
                : manifests) {
            if (manifest.name() == null || manifest.name().isBlank()) {
                errors.add("CAPABILITY_NAME_EMPTY");
            }
            if (!providerNames.contains(manifest.provider())) {
                errors.add("CAPABILITY_PROVIDER_MISSING:" + manifest.name() + ":" + manifest.provider());
            }
            if (!List.of("deterministic", "react", "delegate").contains(manifest.executionMode())) {
                errors.add("CAPABILITY_EXECUTION_MODE_INVALID:" + manifest.name() + ":" + manifest.executionMode());
            }
            if (manifest.sideEffects() && !manifest.requiresHumanApproval()
                    && manifest.permissions().stream().anyMatch(permission -> permission.contains("operation")
                    || permission.contains("execute") || permission.contains("write"))) {
                errors.add("SIDE_EFFECT_WITHOUT_APPROVAL:" + manifest.name());
            }
            manifest.fallbacks().stream()
                    .filter(fallback -> !fallback.isBlank() && !names.contains(fallback))
                    .forEach(fallback -> errors.add("UNKNOWN_FALLBACK:" + manifest.name() + ":" + fallback));
            manifest.composableWith().stream()
                    .filter(target -> !target.isBlank() && !names.contains(target))
                    .forEach(target -> errors.add("UNKNOWN_COMPOSABLE_WITH:" + manifest.name() + ":" + target));
        }
        return errors;
    }
}
