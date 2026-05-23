package com.example.marketing.core.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.workspace.AgentWorkspace;
import com.example.marketing.core.workspace.WorkspaceDocument;
import com.example.marketing.core.workspace.WorkspaceEntry;
import com.example.marketing.core.workspace.WorkspaceSearchHit;

@Service
public class WorkspaceTools {
    private final AgentWorkspace workspace;

    public WorkspaceTools(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    public List<WorkspaceEntry> list(String conversationId, String path) {
        return workspace.list(conversationId, path);
    }

    public WorkspaceDocument read(String conversationId, String path) {
        return workspace.read(conversationId, path);
    }

    public List<WorkspaceSearchHit> search(String conversationId, String path, String query) {
        return workspace.search(conversationId, path, query);
    }

    public Map<String, Object> readableView(String conversationId, String path) {
        WorkspaceDocument document = read(conversationId, path);
        return Map.of(
                "path", document.path(),
                "content", document.content(),
                "metadata", document.metadata(),
                "updatedAt", document.updatedAt().toString()
        );
    }

    public List<Map<String, Object>> searchableView(String conversationId, String path, String query) {
        return search(conversationId, path, query).stream()
                .map(hit -> Map.<String, Object>of(
                        "path", hit.path(),
                        "snippet", hit.snippet(),
                        "score", hit.score(),
                        "metadata", hit.metadata()
                ))
                .toList();
    }
}
