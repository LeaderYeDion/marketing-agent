package com.example.marketing.core.workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class InMemoryAgentWorkspace implements AgentWorkspace {
    private final Map<String, Map<String, WorkspaceDocument>> documents = new ConcurrentHashMap<>();

    @Override
    public List<WorkspaceEntry> list(String conversationId, String path) {
        String prefix = WorkspacePaths.normalize(path);
        Map<String, WorkspaceDocument> scoped = scoped(conversationId);
        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
        scoped.values().stream()
                .filter(document -> WorkspacePaths.under(document.path(), prefix))
                .forEach(document -> addListEntry(entries, prefix, document));
        return entries.values().stream()
                .sorted(Comparator.comparing(WorkspaceEntry::path))
                .toList();
    }

    @Override
    public WorkspaceDocument read(String conversationId, String path) {
        String normalized = WorkspacePaths.normalize(path);
        return scoped(conversationId).getOrDefault(normalized,
                new WorkspaceDocument(normalized, "", Map.of("missing", true), Instant.now()));
    }

    @Override
    public WorkspaceDocument write(String conversationId, String path, String content, Map<String, Object> metadata) {
        WorkspaceDocument document = new WorkspaceDocument(path, content, metadata, Instant.now());
        scoped(conversationId).put(document.path(), document);
        return document;
    }

    @Override
    public WorkspaceDocument edit(String conversationId, String path, WorkspacePatch patch) {
        WorkspacePatch safePatch = patch == null ? new WorkspacePatch("append", "", "") : patch;
        WorkspaceDocument current = read(conversationId, path);
        String next = switch (safePatch.operation()) {
            case "replace" -> current.content().replace(safePatch.target(), safePatch.replacement());
            case "overwrite" -> safePatch.replacement();
            default -> current.content() + safePatch.replacement();
        };
        return write(conversationId, current.path(), next, current.metadata());
    }

    @Override
    public List<WorkspaceSearchHit> search(String conversationId, String path, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        List<WorkspaceSearchHit> hits = new ArrayList<>();
        scoped(conversationId).values().stream()
                .filter(document -> WorkspacePaths.under(document.path(), path))
                .forEach(document -> addSearchHit(hits, document, normalizedQuery));
        return hits.stream()
                .sorted(Comparator.comparing(WorkspaceSearchHit::score).reversed()
                        .thenComparing(WorkspaceSearchHit::path))
                .limit(20)
                .toList();
    }

    @Override
    public WorkspaceStat stat(String conversationId, String path) {
        String normalized = WorkspacePaths.normalize(path);
        WorkspaceDocument document = scoped(conversationId).get(normalized);
        if (document != null) {
            return new WorkspaceStat(normalized, true, false, document.content().length(), document.metadata(),
                    document.updatedAt());
        }
        boolean hasChildren = scoped(conversationId).keySet().stream()
                .anyMatch(candidate -> WorkspacePaths.under(candidate, normalized));
        return new WorkspaceStat(normalized, hasChildren, hasChildren, 0, Map.of(), null);
    }

    private Map<String, WorkspaceDocument> scoped(String conversationId) {
        String key = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
        return documents.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
    }

    private void addListEntry(Map<String, WorkspaceEntry> entries, String prefix, WorkspaceDocument document) {
        String path = document.path();
        if (path.equals(prefix)) {
            entries.put(path, new WorkspaceEntry(path, false, document.content().length(), document.metadata(),
                    document.updatedAt()));
            return;
        }
        String relative = "/".equals(prefix) ? path.substring(1) : path.substring(prefix.length() + 1);
        int slash = relative.indexOf('/');
        if (slash < 0) {
            entries.put(path, new WorkspaceEntry(path, false, document.content().length(), document.metadata(),
                    document.updatedAt()));
            return;
        }
        String childPath = ("/".equals(prefix) ? "" : prefix) + "/" + relative.substring(0, slash);
        entries.putIfAbsent(childPath, new WorkspaceEntry(childPath, true, 0, Map.of(), null));
    }

    private void addSearchHit(List<WorkspaceSearchHit> hits, WorkspaceDocument document, String query) {
        String lower = document.content().toLowerCase();
        int index = lower.indexOf(query);
        if (index < 0) {
            return;
        }
        int start = Math.max(0, index - 80);
        int end = Math.min(document.content().length(), index + query.length() + 160);
        String snippet = document.content().substring(start, end).replace('\n', ' ').trim();
        double score = 1.0 + Math.min(1.0, (double) query.length() / Math.max(1, document.content().length()));
        hits.add(new WorkspaceSearchHit(document.path(), snippet, score, document.metadata()));
    }
}
