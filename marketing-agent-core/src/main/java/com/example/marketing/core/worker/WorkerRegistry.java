package com.example.marketing.core.worker;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class WorkerRegistry {
    private final WorkerManifestRegistry manifestRegistry;

    public WorkerRegistry(WorkerManifestRegistry manifestRegistry) {
        this.manifestRegistry = manifestRegistry;
    }

    public List<WorkerDescriptor> list() {
        return manifestRegistry.list().stream()
                .map(WorkerManifestDescriptor::toWorkerDescriptor)
                .toList();
    }

    public Optional<WorkerDescriptor> find(String name) {
        return list().stream()
                .filter(worker -> worker.name().equals(name))
                .findFirst();
    }

}

