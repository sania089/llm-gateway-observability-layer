package com.gateway.provider;

import com.gateway.common.error.GatewayException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** HTTP client setup and upstream-error mapping shared by every provider client. */
public final class ProviderHttp {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttp.class);
    private static final String UPSTREAM_ERROR = "upstream_error";

    private ProviderHttp() {
    }

    public static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public static void requireApiKey(String provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "server_error",
                    "provider_not_configured", provider + " provider is not configured");
        }
    }

    public static GatewayException failureForStatus(String provider, int status) {
        return failureForStatus(provider, status, null);
    }

    /**
     * Provider messages are not forwarded: they can echo request content or reveal account details.
     * 429, 408 and 5xx are transient and marked retryable; other 4xx are our fault or the caller's and never are.
     */
    public static GatewayException failureForStatus(String provider, int status, HttpHeaders headers) {
        log.warn("{} returned status {}", provider, status);
        return switch (status) {
            case 400, 404, 422 -> new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error",
                    "provider_rejected_request", "The provider rejected the request");
            case 429 -> new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error",
                    "provider_rate_limited", "The provider is rate limiting requests",
                    true, retryAfter(headers));
            case 408, 500, 502, 503, 504 -> new GatewayException(HttpStatus.BAD_GATEWAY, "server_error",
                    UPSTREAM_ERROR, "The provider returned an error", true, retryAfter(headers));
            default -> new GatewayException(HttpStatus.BAD_GATEWAY, "server_error",
                    UPSTREAM_ERROR, "The provider returned an error");
        };
    }

    public static GatewayException malformedStream() {
        return new GatewayException(HttpStatus.BAD_GATEWAY, "server_error",
                UPSTREAM_ERROR, "The provider returned a malformed stream");
    }

    public static GatewayException failureForTransport(String provider, ResourceAccessException ex) {
        boolean timedOut = ex.getCause() instanceof HttpTimeoutException
                || ex.getCause() instanceof SocketTimeoutException;
        log.warn("{} call failed: {}", provider, timedOut ? "timeout" : "connection error");
        return timedOut
                ? new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "server_error",
                        "upstream_timeout", "The provider timed out", true, null)
                : new GatewayException(HttpStatus.BAD_GATEWAY, "server_error",
                        UPSTREAM_ERROR, "Could not reach the provider", true, null);
    }

    /** Parses Retry-After as delta-seconds or an HTTP date; returns null when absent or unparseable. */
    static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
        } catch (NumberFormatException notSeconds) {
            try {
                var at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var delay = Duration.between(Instant.now(), at);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
