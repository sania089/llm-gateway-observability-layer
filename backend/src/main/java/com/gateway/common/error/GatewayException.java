package com.gateway.common.error;

import org.springframework.http.HttpStatus;

/** Business error that maps directly to an HTTP status and an {@link ApiError}. */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;

    public GatewayException(HttpStatus status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String type() { return type; }
    public String code() { return code; }
}
