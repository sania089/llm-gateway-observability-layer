package com.gateway.provider.openai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** apiKey may be blank so the app can boot without it; calls then fail with 503. */
@Validated
@ConfigurationProperties("gateway.providers.openai")
public record OpenAiProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotEmpty List<String> modelPrefixes) {
}
