package com.gateway.auth;

import java.util.Optional;

final class BearerToken {

    private static final String PREFIX = "Bearer ";

    private BearerToken() {
    }

    /** Extracts the token from an {@code Authorization: Bearer <token>} header value. */
    static Optional<String> from(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
