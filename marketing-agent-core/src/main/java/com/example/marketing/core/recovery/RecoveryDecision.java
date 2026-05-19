package com.example.marketing.core.recovery;

public record RecoveryDecision(
        String action,
        String fallbackCapability,
        String reason
) {
    public static RecoveryDecision none(String reason) {
        return new RecoveryDecision("none", "", reason);
    }

    public static RecoveryDecision fallback(String fallbackCapability, String reason) {
        return new RecoveryDecision("fallback", fallbackCapability, reason);
    }

    public static RecoveryDecision askUser(String reason) {
        return new RecoveryDecision("ask_user", "", reason);
    }
}
