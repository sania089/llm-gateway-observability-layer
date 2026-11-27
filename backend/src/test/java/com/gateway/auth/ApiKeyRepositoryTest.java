package com.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
@Import(ApiKeyRepository.class)
class ApiKeyRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsAnActiveKeyByItsHash() {
        var id = UUID.randomUUID();
        repository.insert(id, "hash-active", "gw-abcde", "billing", Instant.now());

        assertThat(repository.findActiveByHash("hash-active"))
                .contains(new ApiKeyPrincipal(id, "billing"));
    }

    @Test
    void doesNotFindUnknownOrRevokedKeys() {
        repository.insert(UUID.randomUUID(), "hash-revoked", "gw-abcde", "billing", Instant.now());
        jdbcTemplate.update("UPDATE api_keys SET status = 'revoked' WHERE key_hash = 'hash-revoked'");

        assertThat(repository.findActiveByHash("hash-revoked")).isEmpty();
        assertThat(repository.findActiveByHash("hash-unknown")).isEmpty();
    }
}
