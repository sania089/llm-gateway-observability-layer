package com.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** key is the secret guarding /admin/v1/**; blank disables the admin API entirely. Set via GATEWAY_ADMIN_KEY. */
@ConfigurationProperties("gateway.admin")
public record AdminProperties(String key) {

    public AdminProperties {
        key = key == null ? "" : key;
    }

    boolean isEnabled() {
        return !key.isBlank();
    }
}
