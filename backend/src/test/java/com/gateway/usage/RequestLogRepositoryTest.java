package com.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs against real Postgres (Flyway migrations included); skipped automatically when Docker is unavailable. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(RequestLogRepository.class)
class RequestLogRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RequestLogRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertsBatchWithNullableTokensAndDefaultFlags() {
        var keyId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO api_keys (id, key_hash, key_prefix, owner, created_at) "
                + "VALUES (?, 'h', 'gw-abcde', 'o', now())", keyId);
        var withTokens = new RequestLog(Instant.now(), keyId, "gemini-2.0-flash", "gemini", 120, 200, 3, 4, 7, null);
        var failed = new RequestLog(Instant.now(), keyId, "mystery", null, 2, 404, null, null, null, "model_not_found");

        repository.insertAll(List.of(withTokens, failed));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM request_logs ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("total_tokens", 7).containsEntry("cache_hit", false)
                .containsEntry("fallback_used", false);
        assertThat(rows.get(1)).containsEntry("total_tokens", null).containsEntry("provider", null)
                .containsEntry("error_code", "model_not_found");
    }
}
