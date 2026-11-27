package com.gateway.provider.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class ConfiguredProvidersHealthTest {

    private static ProviderProperties providerWithKey(String apiKey) {
        return new ProviderProperties("http://localhost", apiKey, Duration.ofSeconds(1), Duration.ofSeconds(1),
                List.of("m-"));
    }

    @Test
    void reportsWhichProvidersHaveAKeyWithoutMarkingTheAppDown() {
        var properties = new ProvidersProperties(Map.of(
                "gemini", providerWithKey("secret-key"),
                "groq", providerWithKey("")));

        var health = new ConfiguredProvidersHealth(properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("gemini", ConfiguredProvidersHealth.CONFIGURED)
                .containsEntry("groq", ConfiguredProvidersHealth.MISSING_API_KEY)
                .doesNotContainValue("secret-key");
    }
}
