package com.example.marketing.core.workspace;

import java.time.Instant;
import java.util.Map;

public record WorkspaceStat(
        String path,
        boolean exists,
        boolean directory,
        long size,
        Map<String, Object> metadata,
        Instant updatedAt
) {
    public WorkspaceStat {
        path = WorkspacePaths.normalize(path);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
