package com.gateway.proxy;

import com.gateway.auth.ApiKeyPrincipal;
import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import com.gateway.usage.RequestLog;
import com.gateway.usage.RequestLogWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
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
    /** nginx's convention for "client closed the request"; there is no standard HTTP status for it. */
    private static final int CLIENT_CLOSED_STATUS = 499;

    private final List<ProviderClient> providers;
    private final MeterRegistry meterRegistry;
    private final ProxyProperties properties;
    private final RequestLogWriter requestLogWriter;

    public ProxyService(List<ProviderClient> providers, MeterRegistry meterRegistry, ProxyProperties properties,
                        RequestLogWriter requestLogWriter) {
        this.providers = List.copyOf(providers);
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.requestLogWriter = requestLogWriter;
    }

    /** One call being logged: who made it, for which model, and when it started. */
    private record Call(ApiKeyPrincipal caller, String model, Instant startedAt) {
        static Call start(ApiKeyPrincipal caller, String model) {
            return new Call(caller, model, Instant.now());
        }
    }

    public ChatResponse chat(ChatRequest original, ApiKeyPrincipal caller) {
        var call = Call.start(caller, original.model());
        try {
            var request = enforceLimits(original);
            var provider = findProvider(request.model());
            var response = timed(provider, () -> provider.chat(request));
            logSuccess(call, response.usage());
            return response;
        } catch (RuntimeException ex) {
            logFailure(call, ex);
            throw ex;
        }
    }

    /**
     * Streams chunks to {@code onChunk} and returns the token usage reported in the final chunk
     * (empty if the provider never sent one, e.g. the stream was aborted).
     */
    public Optional<ChatResponse.Usage> stream(ChatRequest original, ApiKeyPrincipal caller,
                                               Consumer<ChatChunk> onChunk) {
        var call = Call.start(caller, original.model());
        try {
            var usage = streamFromProvider(original, onChunk);
            logSuccess(call, usage.orElse(null));
            return usage;
        } catch (RuntimeException ex) {
            logFailure(call, ex);
            throw ex;
        }
    }

    private Optional<ChatResponse.Usage> streamFromProvider(ChatRequest original, Consumer<ChatChunk> onChunk) {
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

    private Optional<ProviderClient> lookupProvider(String model) {
        return providers.stream().filter(provider -> provider.supports(model)).findFirst();
    }

    private ProviderClient findProvider(String model) {
        return lookupProvider(model)
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

    private void logSuccess(Call call, ChatResponse.Usage usage) {
        submitLog(call, HttpStatus.OK.value(), usage, null);
    }

    private void logFailure(Call call, RuntimeException failure) {
        switch (failure) {
            case GatewayException ex -> submitLog(call, ex.status().value(), null, ex.code());
            case ClientDisconnectedException ex -> submitLog(call, CLIENT_CLOSED_STATUS, null, "client_disconnected");
            default -> submitLog(call, HttpStatus.INTERNAL_SERVER_ERROR.value(), null, "internal_error");
        }
    }

    private void submitLog(Call call, int status, ChatResponse.Usage usage, String errorCode) {
        String providerName = lookupProvider(call.model()).map(ProviderClient::name).orElse(null);
        long latencyMs = Duration.between(call.startedAt(), Instant.now()).toMillis();
        requestLogWriter.submit(new RequestLog(call.startedAt(), call.caller().id(), call.model(), providerName,
                (int) latencyMs, status,
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                errorCode));
    }
}
