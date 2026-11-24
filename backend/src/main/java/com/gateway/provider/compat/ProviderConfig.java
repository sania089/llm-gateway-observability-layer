package com.gateway.provider.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adding another OpenAI-compatible provider is a YAML block under gateway.providers, not new code. */
@Configuration
class ProviderConfig {

    @Bean
    List<ProviderClient> providerClients(ProvidersProperties properties, ObjectMapper objectMapper,
                                         MeterRegistry meterRegistry) {
        return properties.providers().entrySet().stream()
                .<ProviderClient>map(entry -> new ResilientProviderClient(
                        new OpenAiCompatibleClient(entry.getKey(), entry.getValue(), objectMapper),
                        entry.getValue(), meterRegistry))
                .toList();
    }
}
