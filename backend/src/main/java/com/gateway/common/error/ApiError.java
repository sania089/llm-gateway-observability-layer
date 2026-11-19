package com.gateway.common.error;

/** OpenAI-style error envelope: {"error": {"message", "type", "code"}}. */
public record ApiError(Body error) {

    public record Body(String message, String type, String code) {}

    public static ApiError of(String message, String type, String code) {
        return new ApiError(new Body(message, type, code));
    }
}
