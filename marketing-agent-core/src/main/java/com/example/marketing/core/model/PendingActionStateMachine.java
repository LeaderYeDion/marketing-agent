package com.example.marketing.core.model;

import org.springframework.stereotype.Service;

@Service
public class PendingActionStateMachine {
    public PendingAction approve(PendingAction action, String userId) {
        requireActive(action);
        return action.withStatus(PendingActionStatus.APPROVED, userId);
    }

    public PendingAction reject(PendingAction action, String userId) {
        requireActive(action);
        return action.withStatus(PendingActionStatus.REJECTED, userId);
    }

    public PendingAction edit(PendingAction action, java.util.Map<String, Object> editedPayload, String userId) {
        requireActive(action);
        return action.withEditedPayload(editedPayload, userId);
    }

    public PendingAction expire(PendingAction action, String userId) {
        requireActive(action);
        return action.withStatus(PendingActionStatus.EXPIRED, userId);
    }

    public PendingAction executed(PendingAction action, String userId) {
        if (!PendingActionStatus.APPROVED.equals(action.status())) {
            throw new IllegalStateException("Pending action must be approved before execution: " + action.id());
        }
        return action.withStatus(PendingActionStatus.EXECUTED, userId);
    }

    private void requireActive(PendingAction action) {
        if (action == null || !action.isPending()) {
            throw new IllegalStateException("Pending action is not active");
        }
    }
}
