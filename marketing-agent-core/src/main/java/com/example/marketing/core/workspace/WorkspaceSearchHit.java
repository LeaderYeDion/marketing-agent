package com.example.marketing.core.workspace;

import java.util.Map;

public record WorkspaceSearchHit(
        String path,
        String snippet,
        double score,
        Map<String, Object> metadata
) {
    public WorkspaceSearchHit {
        path = WorkspacePaths.normalize(path);
        snippet = snippet == null ? "" : snippet;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
