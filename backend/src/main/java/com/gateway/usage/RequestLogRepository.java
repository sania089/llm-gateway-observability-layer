package com.gateway.usage;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RequestLogRepository {

    private static final String INSERT = """
            INSERT INTO request_logs (created_at, api_key_id, model, provider, latency_ms, status,
                                      prompt_tokens, completion_tokens, total_tokens, error_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbcTemplate;

    RequestLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insertAll(List<RequestLog> logs) {
        jdbcTemplate.batchUpdate(INSERT, logs, logs.size(), (statement, log) -> {
            statement.setObject(1, OffsetDateTime.ofInstant(log.createdAt(), ZoneOffset.UTC));
            statement.setObject(2, log.apiKeyId());
            statement.setString(3, log.model());
            statement.setString(4, log.provider());
            statement.setInt(5, log.latencyMs());
            statement.setInt(6, log.status());
            statement.setObject(7, log.promptTokens(), Types.INTEGER);
            statement.setObject(8, log.completionTokens(), Types.INTEGER);
            statement.setObject(9, log.totalTokens(), Types.INTEGER);
            statement.setString(10, log.errorCode());
        });
    }
}
