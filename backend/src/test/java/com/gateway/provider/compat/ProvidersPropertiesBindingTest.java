package com.gateway.provider.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Binds the real application.yml, which building the records in Java would not exercise. */
class ProvidersPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProvidersProperties.class)
    static class BindingConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindingConfig.class);

    @Test
    void bindsProvidersFromApplicationYml() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var providers = context.getBean(ProvidersProperties.class).providers();

            assertThat(providers).containsOnlyKeys("gemini", "groq");
            assertThat(providers.values()).allSatisfy(provider -> {
                assertThat(provider.baseUrl()).isNotBlank();
                assertThat(provider.modelPrefixes()).isNotEmpty();
                assertThat(provider.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(provider.readTimeout()).isEqualTo(Duration.ofSeconds(60));
                assertThat(provider.maxBackoff()).isEqualTo(Duration.ofSeconds(8));
            });
        });
    }
}
