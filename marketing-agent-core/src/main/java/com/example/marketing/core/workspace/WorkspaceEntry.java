package com.example.marketing.core.workspace;

import java.time.Instant;
import java.util.Map;

public record WorkspaceEntry(
        String path,
        boolean directory,
        long size,
        Map<String, Object> metadata,
        Instant updatedAt
) {
    public WorkspaceEntry {
        path = WorkspacePaths.normalize(path);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
