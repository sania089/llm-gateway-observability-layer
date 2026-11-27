package com.gateway.usage;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * queueCapacity bounds memory: when the database is slow and the queue is full, new entries are dropped
 * (and counted) rather than slowing requests. flushInterval is read by the writer's {@code @Scheduled} directly.
 */
@Validated
@ConfigurationProperties("gateway.usage")
public record UsageProperties(@Min(1) int queueCapacity, @Min(1) int batchSize, @NotNull Duration flushInterval) {
}
