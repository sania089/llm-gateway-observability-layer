package com.gateway.provider.compat;

import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reports which providers have an API key. A missing key never blocks startup or marks the app DOWN
 * (the other provider may still work), so it is a startup warning plus a health detail.
 */
@Component
class ConfiguredProvidersHealth implements HealthIndicator {

    static final String CONFIGURED = "configured";
    static final String MISSING_API_KEY = "missing_api_key";

    private static final Logger log = LoggerFactory.getLogger(ConfiguredProvidersHealth.class);

    private final ProvidersProperties properties;

    ConfiguredProvidersHealth(ProvidersProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        return Health.up().withDetails(statusByProvider()).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnAboutMissingKeys() {
        statusByProvider().forEach((provider, status) -> {
            if (MISSING_API_KEY.equals(status)) {
                log.warn("No API key for provider '{}': calls to it will fail until its key env var is set", provider);
            }
        });
    }

    private Map<String, Object> statusByProvider() {
        var statuses = new TreeMap<String, Object>();
        properties.providers().forEach((name, provider) -> statuses.put(name,
                provider.apiKey() == null || provider.apiKey().isBlank() ? MISSING_API_KEY : CONFIGURED));
        return statuses;
    }
}
