package com.gateway.proxy;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * streamTimeout caps the total lifetime of one client SSE connection.
 * maxInputChars rejects oversized prompts (413) before any provider quota is spent;
 * maxOutputTokens is applied when the client sends no max_tokens or asks for more.
 */
@Validated
@ConfigurationProperties("gateway.proxy")
public record ProxyProperties(@NotNull Duration streamTimeout, Integer maxInputChars, Integer maxOutputTokens) {

    public ProxyProperties {
        maxInputChars = maxInputChars == null ? 100_000 : maxInputChars;
        maxOutputTokens = maxOutputTokens == null ? 2_048 : maxOutputTokens;
    }
}
