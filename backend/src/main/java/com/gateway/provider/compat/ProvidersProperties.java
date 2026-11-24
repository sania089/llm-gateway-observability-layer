package com.gateway.provider.compat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Keyed by provider name (the low-cardinality label used in metrics), e.g. gemini, groq. */
@Validated
@ConfigurationProperties("gateway")
public record ProvidersProperties(@NotEmpty Map<String, @Valid ProviderProperties> providers) {
}
