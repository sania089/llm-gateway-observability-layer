package com.gateway.auth;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

/** Returned once at creation; {@code key} is the only time the raw key is ever visible. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IssuedApiKey(UUID id, String owner, String keyPrefix, String key, Instant createdAt) {
}
