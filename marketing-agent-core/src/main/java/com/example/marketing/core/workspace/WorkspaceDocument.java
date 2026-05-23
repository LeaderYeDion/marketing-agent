package com.example.marketing.core.workspace;

import java.time.Instant;
import java.util.Map;

public record WorkspaceDocument(
        String path,
        String content,
        Map<String, Object> metadata,
        Instant updatedAt
) {
    public WorkspaceDocument {
        path = WorkspacePaths.normalize(path);
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
