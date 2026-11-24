package com.gateway.provider.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.provider.ProviderClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderConfigTest {

    private static ProviderProperties providerWithPrefix(String prefix) {
        return new ProviderProperties("http://localhost", "key",
                Duration.ofSeconds(1), Duration.ofSeconds(1), List.of(prefix));
    }

    @Test
    void createsOneClientPerConfiguredProviderNamedByItsKey() {
        var properties = new ProvidersProperties(
                Map.of("gemini", providerWithPrefix("gemini-"), "groq", providerWithPrefix("llama-")));

        var clients = new ProviderConfig().providerClients(properties, new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(clients).extracting(ProviderClient::name).containsExactlyInAnyOrder("gemini", "groq");
        assertThat(clients).filteredOn(client -> client.supports("llama-3.3-70b-versatile")).extracting(ProviderClient::name)
                .containsExactly("groq");
    }
}
