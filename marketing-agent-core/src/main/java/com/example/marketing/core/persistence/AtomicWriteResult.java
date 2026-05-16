package com.example.marketing.core.persistence;

public record AtomicWriteResult(
        boolean applied,
        long previousVersion,
        long nextVersion,
        String reason
) {
    public static AtomicWriteResult applied(long previousVersion, long nextVersion) {
        return new AtomicWriteResult(true, previousVersion, nextVersion, "");
    }

    public static AtomicWriteResult skipped(long currentVersion, String reason) {
        return new AtomicWriteResult(false, currentVersion, currentVersion, reason == null ? "" : reason);
    }
}
