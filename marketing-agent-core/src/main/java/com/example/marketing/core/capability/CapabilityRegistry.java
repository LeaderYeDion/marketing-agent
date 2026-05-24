package com.example.marketing.core.capability;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class CapabilityRegistry {
    private final CapabilityManifestRegistry manifestRegistry;

    public CapabilityRegistry(CapabilityManifestRegistry manifestRegistry) {
        this.manifestRegistry = manifestRegistry;
    }

    public List<CapabilityDescriptor> list() {
        return manifestRegistry.list().stream()
                .map(CapabilityManifestDescriptor::toCapabilityDescriptor)
                .toList();
    }

    public Optional<CapabilityDescriptor> find(String name) {
        return list().stream()
                .filter(capability -> capability.name().equals(name))
                .findFirst();
    }

}
