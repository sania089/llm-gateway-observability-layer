package com.gateway.proxy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProxyService {

    private static final String METRIC_NAME = "gateway.provider.requests";

    private final List<ProviderClient> providers;
    private final MeterRegistry meterRegistry;
    private final ProxyProperties properties;

    public ProxyService(List<ProviderClient> providers, MeterRegistry meterRegistry, ProxyProperties properties) {
        this.providers = List.copyOf(providers);
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    public ChatResponse chat(ChatRequest original) {
        var request = enforceLimits(original);
        var provider = findProvider(request.model());
        return timed(provider, () -> provider.chat(request));
    }

    /**
     * Streams chunks to {@code onChunk} and returns the token usage reported in the final chunk
     * (empty if the provider never sent one, e.g. the stream was aborted).
     */
    public Optional<ChatResponse.Usage> stream(ChatRequest original, Consumer<ChatChunk> onChunk) {
        var request = enforceLimits(original);
        var provider = findProvider(request.model());
        var usage = new AtomicReference<ChatResponse.Usage>();
        timed(provider, () -> {
            provider.streamChat(request, chunk -> {
                if (chunk.usage() != null) {
                    usage.set(chunk.usage());
                }
                onChunk.accept(chunk);
            });
            return null;
        });
        return Optional.ofNullable(usage.get());
    }

    /** Rejects oversized prompts and caps output so one request cannot drain a free-tier quota. */
    private ChatRequest enforceLimits(ChatRequest request) {
        long inputChars = request.messages().stream().mapToLong(message -> message.content().length()).sum();
        if (inputChars > properties.maxInputChars()) {
            throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "invalid_request_error",
                    "request_too_large", "The prompt exceeds the gateway limit of "
                            + properties.maxInputChars() + " characters");
        }
        Integer requested = request.maxTokens();
        if (requested != null && requested < 1) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error",
                    "invalid_max_tokens", "max_tokens must be at least 1");
        }
        int cap = properties.maxOutputTokens();
        return requested == null || requested > cap ? request.withMaxTokens(cap) : request;
    }

    private ProviderClient findProvider(String model) {
        return providers.stream()
                .filter(provider -> provider.supports(model))
                .findFirst()
                .orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "invalid_request_error",
                        "model_not_found", "The model '" + model + "' is not supported"));
    }

    private <T> T timed(ProviderClient provider, Supplier<T> call) {
        var sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            var result = call.get();
            outcome = "success";
            return result;
        } finally {
            sample.stop(Timer.builder(METRIC_NAME)
                    .tag("provider", provider.name())
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}
