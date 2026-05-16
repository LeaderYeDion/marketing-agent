package com.example.marketing.core.persistence;

import java.util.List;
import java.util.Optional;

import com.example.marketing.core.model.PendingAction;
import com.example.marketing.core.model.PendingActionStatus;
import com.example.marketing.core.operation.OperationRecord;
import com.example.marketing.core.state.ConversationSnapshot;
import com.example.marketing.core.task.AgentTask;

public interface AgentAtomicPersistence {
    Optional<ConversationRecord> loadConversation(String conversationId);

    AtomicWriteResult compareAndSaveConversation(ConversationSnapshot snapshot, long expectedVersion);

    AtomicWriteResult insertPendingActionIfAbsent(PendingAction action);

    AtomicWriteResult transitionPendingAction(String actionId, PendingActionStatus expectedStatus,
                                              PendingAction nextAction);

    Optional<OperationRecord> findOperationByIdempotencyKey(String idempotencyKey);

    AtomicWriteResult insertOperationIfAbsent(OperationRecord operation);

    AtomicWriteResult updateOperation(OperationRecord operation);

    AtomicWriteResult upsertTask(AgentTask task);

    AtomicWriteResult appendOutboxEvent(OutboxEvent event);

    List<OutboxEvent> claimOutboxEvents(int limit);
}
