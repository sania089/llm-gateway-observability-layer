package com.gateway.auth;

import com.gateway.common.error.GatewayException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class AdminAuthorizer {

    private final AdminProperties properties;

    AdminAuthorizer(AdminProperties properties) {
        this.properties = properties;
    }

    void requireAdmin(String authorizationHeader) {
        if (!properties.isEnabled()) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "invalid_request_error", "admin_disabled",
                    "The admin API is disabled. Set GATEWAY_ADMIN_KEY to enable it.");
        }
        boolean authorized = BearerToken.from(authorizationHeader).filter(this::matchesAdminKey).isPresent();
        if (!authorized) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_request_error", "invalid_admin_key",
                    "Missing or invalid admin key");
        }
    }

    private boolean matchesAdminKey(String candidate) {
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), properties.key().getBytes(StandardCharsets.UTF_8));
    }
}
