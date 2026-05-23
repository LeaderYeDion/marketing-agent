package com.example.marketing.core.workspace;

final class WorkspacePaths {
    private WorkspacePaths() {
    }

    static String normalize(String path) {
        String value = path == null || path.isBlank() ? "/" : path.trim().replace('\\', '/');
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static boolean under(String path, String prefix) {
        String normalizedPath = normalize(path);
        String normalizedPrefix = normalize(prefix);
        return "/".equals(normalizedPrefix)
                || normalizedPath.equals(normalizedPrefix)
                || normalizedPath.startsWith(normalizedPrefix + "/");
    }

    static String parent(String path) {
        String normalized = normalize(path);
        int index = normalized.lastIndexOf('/');
        return index <= 0 ? "/" : normalized.substring(0, index);
    }
}
