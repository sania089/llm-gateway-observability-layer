package com.gateway.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns every failure into the OpenAI-style error body.
 * Spring MVC's own exceptions (validation, bad JSON, 404, 405...) are routed through
 * {@link #handleExceptionInternal} by the parent class.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<ApiError> handleGateway(GatewayException ex) {
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.getMessage(), ex.type(), ex.code()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("Internal server error", "server_error", "internal_error"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        boolean clientError = status.is4xxClientError();
        String type = clientError ? "invalid_request_error" : "server_error";
        String message = clientError ? ex.getMessage() : "Internal server error";
        String code = HttpStatus.valueOf(status.value()).name().toLowerCase();
        return ResponseEntity.status(status).headers(headers).body(ApiError.of(message, type, code));
    }
}
