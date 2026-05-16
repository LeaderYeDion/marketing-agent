package com.example.marketing.core.persistence;

public final class MySqlAgentPersistenceSql {
    public static final String CONVERSATION_TABLE = "agent_conversation";
    public static final String PENDING_ACTION_TABLE = "agent_pending_action";
    public static final String OPERATION_TABLE = "agent_operation";
    public static final String TASK_TABLE = "agent_task";
    public static final String OUTBOX_TABLE = "agent_outbox_event";

    public static final String LOAD_CONVERSATION = """
            select conversation_id, snapshot_json, version, created_at, updated_at
            from agent_conversation
            where conversation_id = ?
            """;

    public static final String COMPARE_AND_SAVE_CONVERSATION = """
            insert into agent_conversation(conversation_id, snapshot_json, version, created_at, updated_at)
            values (?, ?, 1, current_timestamp, current_timestamp)
            on duplicate key update
              snapshot_json = if(version = ?, values(snapshot_json), snapshot_json),
              version = if(version = ?, version + 1, version),
              updated_at = if(version = ?, current_timestamp, updated_at)
            """;

    public static final String INSERT_PENDING_ACTION_IF_ABSENT = """
            insert ignore into agent_pending_action(
              action_id, conversation_id, source_agent, invocation_id, visible_object_id,
              status, payload_json, version, created_at, expires_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
            """;

    public static final String TRANSITION_PENDING_ACTION = """
            update agent_pending_action
            set status = ?, payload_json = ?, version = version + 1, decided_at = ?, decided_by = ?,
                updated_at = current_timestamp
            where action_id = ? and status = ? and version = ?
            """;

    public static final String INSERT_OPERATION_IF_ABSENT = """
            insert ignore into agent_operation(
              operation_id, idempotency_key, operation_type, status, request_payload_json,
              result_payload_json, error_message, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    public static final String UPDATE_OPERATION = """
            update agent_operation
            set status = ?, result_payload_json = ?, error_message = ?, updated_at = ?
            where operation_id = ?
            """;

    public static final String APPEND_OUTBOX_EVENT = """
            insert into agent_outbox_event(
              event_id, aggregate_type, aggregate_id, event_type, status,
              payload_json, publish_attempts, last_error, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private MySqlAgentPersistenceSql() {
    }
}
