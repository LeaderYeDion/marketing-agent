package com.example.marketing.core.workspace;

import java.util.List;
import java.util.Map;

public interface AgentWorkspace {
    List<WorkspaceEntry> list(String conversationId, String path);

    WorkspaceDocument read(String conversationId, String path);

    WorkspaceDocument write(String conversationId, String path, String content, Map<String, Object> metadata);

    WorkspaceDocument edit(String conversationId, String path, WorkspacePatch patch);

    List<WorkspaceSearchHit> search(String conversationId, String path, String query);

    WorkspaceStat stat(String conversationId, String path);
}
