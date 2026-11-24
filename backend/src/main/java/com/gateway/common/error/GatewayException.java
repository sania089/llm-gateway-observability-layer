package com.gateway.common.error;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * Business error that maps directly to an HTTP status and an {@link ApiError}.
 * {@code retryable} marks transient upstream failures the provider layer may retry;
 * {@code retryAfter} is the upstream's (or the local throttle's) hint, echoed as a Retry-After header.
 */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final boolean retryable;
    private final Duration retryAfter;

    public GatewayException(HttpStatus status, String type, String code, String message) {
        this(status, type, code, message, false, null);
    }

    public GatewayException(HttpStatus status, String type, String code, String message,
                            boolean retryable, Duration retryAfter) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public HttpStatus status() { return status; }
    public String type() { return type; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    /** May be null when no hint is available. */
    public Duration retryAfter() { return retryAfter; }
}
