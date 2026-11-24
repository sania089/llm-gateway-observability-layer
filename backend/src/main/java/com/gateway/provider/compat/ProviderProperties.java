package com.gateway.provider.compat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;

/**
 * apiKey may be blank so the app can boot without it; calls then fail with 503.
 *
 * <p>Free-tier guards (all optional):
 * <ul>
 *   <li>maxAttempts: total tries per call incl. the first (default 3); retries only on 429/5xx/timeouts.</li>
 *   <li>initialBackoff / maxBackoff: exponential backoff bounds; a Retry-After above maxBackoff is not waited for.</li>
 *   <li>requestsPerMinute: local cap kept below the provider's free quota (0 = unlimited).</li>
 *   <li>maxConcurrent: simultaneous in-flight calls (0 = unlimited).</li>
 *   <li>throttleMaxWait: how long a call may queue for a local slot before getting a 429.</li>
 * </ul>
 */
public record ProviderProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotEmpty List<String> modelPrefixes,
        Integer maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Integer requestsPerMinute,
        Integer maxConcurrent,
        Duration throttleMaxWait) {

    public ProviderProperties {
        maxAttempts = maxAttempts == null ? 3 : Math.max(1, maxAttempts);
        initialBackoff = initialBackoff == null ? Duration.ofMillis(500) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(8) : maxBackoff;
        requestsPerMinute = requestsPerMinute == null ? 0 : Math.max(0, requestsPerMinute);
        maxConcurrent = maxConcurrent == null ? 0 : Math.max(0, maxConcurrent);
        throttleMaxWait = throttleMaxWait == null ? Duration.ofSeconds(5) : throttleMaxWait;
    }

    public ProviderProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
                              List<String> modelPrefixes) {
        this(baseUrl, apiKey, connectTimeout, readTimeout, modelPrefixes, null, null, null, null, null, null);
    }
}
