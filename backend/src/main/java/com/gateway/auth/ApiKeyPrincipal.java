package com.gateway.auth;

import java.util.UUID;

/** The authenticated caller of a gateway request. */
public record ApiKeyPrincipal(UUID id, String owner) {

    /** Request attribute under which the auth filter stores the caller for the controller. */
    public static final String REQUEST_ATTRIBUTE = "gateway.apiKeyPrincipal";
}
