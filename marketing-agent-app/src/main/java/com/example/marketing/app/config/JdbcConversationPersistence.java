package com.example.marketing.app.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.marketing.core.state.ConversationPersistence;
import com.example.marketing.core.state.ConversationSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Profile("prod")
public class JdbcConversationPersistence implements ConversationPersistence {
    private final ObjectMapper objectMapper;
    private final String url;
    private final String username;
    private final String password;

    public JdbcConversationPersistence(ObjectMapper objectMapper,
                                       @Value("${agent.mysql.url}") String url,
                                       @Value("${agent.mysql.username}") String username,
                                       @Value("${agent.mysql.password}") String password) {
        this.objectMapper = objectMapper;
        this.url = url;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public Optional<ConversationSnapshot> load(String conversationId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "select snapshot_json from agent_conversation_snapshot where conversation_id = ?")) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(objectMapper.readValue(resultSet.getString(1), ConversationSnapshot.class));
            }
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to load conversation snapshot: " + conversationId, ex);
        }
    }

    @Override
    public void save(ConversationSnapshot snapshot) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into agent_conversation_snapshot(conversation_id, snapshot_json, updated_at)
                     values (?, ?, current_timestamp)
                     on duplicate key update snapshot_json = values(snapshot_json), updated_at = current_timestamp
                     """)) {
            statement.setString(1, snapshot.conversationId());
            statement.setString(2, toJson(snapshot));
            statement.executeUpdate();
        }
        catch (SQLException ex) {
            throw new IllegalStateException("Failed to save conversation snapshot: " + snapshot.conversationId(), ex);
        }
    }

    private void ensureSchema() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     create table if not exists agent_conversation_snapshot (
                       conversation_id varchar(128) primary key,
                       snapshot_json json not null,
                       updated_at timestamp not null default current_timestamp
                     )
                     """)) {
            statement.execute();
        }
        catch (SQLException ex) {
            throw new IllegalStateException("Failed to initialize conversation snapshot schema", ex);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private String toJson(ConversationSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize conversation snapshot", ex);
        }
    }
}
