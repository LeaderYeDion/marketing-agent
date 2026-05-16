package com.example.marketing.core.rag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.example.marketing.core.context.MarketingAgentContext;

@Service
@Profile("pgvector")
public class PgVectorMarketingRagService implements RagService {
    private final EmbeddingService embeddingService;
    private final String url;
    private final String username;
    private final String password;
    private final String tableName;
    private final int topK;

    public PgVectorMarketingRagService(EmbeddingService embeddingService,
                                       @Value("${agent.pgvector.url:jdbc:postgresql://localhost:5432/marketing_agent}") String url,
                                       @Value("${agent.pgvector.username:postgres}") String username,
                                       @Value("${agent.pgvector.password:}") String password,
                                       @Value("${agent.pgvector.table:marketing_knowledge_chunk}") String tableName,
                                       @Value("${agent.pgvector.top-k:5}") int topK) {
        this.embeddingService = embeddingService;
        this.url = url;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.topK = topK;
    }

    @Override
    public List<RagDocument> retrieve(String query, MarketingAgentContext context) {
        float[] embedding = embeddingService.embed(buildSearchText(query, context));
        String vector = toPgVector(embedding);
        List<RagChunk> chunks = retrieveChunks(vector, context);
        return chunks.stream().map(RagChunk::toDocument).toList();
    }

    private List<RagChunk> retrieveChunks(String vector, MarketingAgentContext context) {
        String sql = """
                select document_id, chunk_id, title, content, source_uri, version,
                       1 - (embedding <=> ?::vector) as score
                from %s
                where (tenant_id is null or tenant_id = ?)
                  and (effective_from is null or effective_from <= current_timestamp)
                  and (effective_to is null or effective_to > current_timestamp)
                order by embedding <=> ?::vector
                limit ?
                """.formatted(tableName);
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vector);
            statement.setString(2, tenantId(context));
            statement.setString(3, vector);
            statement.setInt(4, topK);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RagChunk> chunks = new ArrayList<>();
                while (resultSet.next()) {
                    double score = resultSet.getDouble("score");
                    chunks.add(new RagChunk(
                            resultSet.getString("document_id"),
                            resultSet.getString("chunk_id"),
                            resultSet.getString("title"),
                            resultSet.getString("content"),
                            resultSet.getString("source_uri"),
                            resultSet.getString("version"),
                            score,
                            confidence(score),
                            citation(resultSet),
                            Map.of("retriever", "pgvector")
                    ));
                }
                return chunks;
            }
        }
        catch (SQLException ex) {
            throw new IllegalStateException("Failed to retrieve marketing knowledge from pgvector", ex);
        }
    }

    private String buildSearchText(String query, MarketingAgentContext context) {
        return String.join(" ", query == null ? "" : query, context.product(), context.audience(), context.channel(),
                String.join(" ", context.goals()));
    }

    private String tenantId(MarketingAgentContext context) {
        Object tenantId = context.variables().get("tenant_id");
        return tenantId == null ? "default" : tenantId.toString();
    }

    private double confidence(double score) {
        return Math.max(0, Math.min(1, score));
    }

    private String citation(ResultSet resultSet) throws SQLException {
        return resultSet.getString("title") + "@" + resultSet.getString("version")
                + "#" + resultSet.getString("chunk_id");
    }

    private String toPgVector(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
