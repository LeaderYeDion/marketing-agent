package com.example.marketing.core.recovery;

public record RecoveryDecision(
        String action,
        String fallbackWorker,
        String reason
) {
    public static RecoveryDecision none(String reason) {
        return new RecoveryDecision("none", "", reason);
    }

    public static RecoveryDecision fallback(String fallbackWorker, String reason) {
        return new RecoveryDecision("fallback", fallbackWorker, reason);
    }

    public static RecoveryDecision askUser(String reason) {
        return new RecoveryDecision("ask_user", "", reason);
    }

    public static RecoveryDecision replan(String reason) {
        return new RecoveryDecision("replan", "", reason);
    }
}

