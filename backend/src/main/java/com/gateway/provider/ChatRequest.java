package com.gateway.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** OpenAI-compatible chat completion request; also the gateway's internal model. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Boolean stream,
        StreamOptions streamOptions) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(boolean includeUsage) {
    }

    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }

    public ChatRequest withMaxTokens(Integer newMaxTokens) {
        return new ChatRequest(model, messages, temperature, topP, newMaxTokens, stream, streamOptions);
    }

    /** Usage is needed at end of stream for cost tracking, whatever the client asked for. */
    public ChatRequest withUsageInStream() {
        return new ChatRequest(model, messages, temperature, topP, maxTokens, stream, new StreamOptions(true));
    }
}
