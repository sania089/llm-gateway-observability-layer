package com.gateway.usage;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code request_logs}. Deliberately has no prompt or completion text. */
public record RequestLog(
        Instant createdAt,
        UUID apiKeyId,
        String model,
        String provider,
        int latencyMs,
        int status,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String errorCode) {

    /** Matches the column width; the model name is client-supplied, so a long one must not fail a whole batch. */
    static final int MAX_MODEL_LENGTH = 100;

    public RequestLog {
        model = model.length() > MAX_MODEL_LENGTH ? model.substring(0, MAX_MODEL_LENGTH) : model;
    }
}
