package com.gateway.auth;

import com.gateway.common.error.GatewayException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Authenticates every {@code /v1/**} call by its gateway key; anything unrecognised is rejected (fail closed). */
@Component
class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PATH_PREFIX = "/v1/";

    private final ApiKeyService apiKeyService;
    private final HandlerExceptionResolver exceptionResolver;

    ApiKeyAuthFilter(ApiKeyService apiKeyService,
                     @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.apiKeyService = apiKeyService;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var principal = BearerToken.from(request.getHeader(HttpHeaders.AUTHORIZATION))
                .flatMap(apiKeyService::authenticate);
        if (principal.isEmpty()) {
            // Delegating to the resolver keeps the 401 body identical to every other error.
            exceptionResolver.resolveException(request, response, null, new GatewayException(
                    HttpStatus.UNAUTHORIZED, "invalid_request_error", "invalid_api_key",
                    "Missing or invalid API key. Send 'Authorization: Bearer gw-...'"));
            return;
        }
        request.setAttribute(ApiKeyPrincipal.REQUEST_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }
}
