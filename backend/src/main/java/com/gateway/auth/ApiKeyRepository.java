package com.gateway.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ApiKeyRepository {

    private static final String INSERT = """
            INSERT INTO api_keys (id, key_hash, key_prefix, owner, created_at)
            VALUES (?, ?, ?, ?, ?)""";
    private static final String FIND_ACTIVE_BY_HASH = """
            SELECT id, owner FROM api_keys WHERE key_hash = ? AND status = 'active'""";

    private final JdbcTemplate jdbcTemplate;

    ApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insert(UUID id, String keyHash, String keyPrefix, String owner, Instant createdAt) {
        jdbcTemplate.update(INSERT, id, keyHash, keyPrefix, owner, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    Optional<ApiKeyPrincipal> findActiveByHash(String keyHash) {
        return jdbcTemplate.query(FIND_ACTIVE_BY_HASH,
                        (rs, rowNum) -> new ApiKeyPrincipal(rs.getObject("id", UUID.class), rs.getString("owner")),
                        keyHash)
                .stream().findFirst();
    }
}
