package com.gateway.provider.compat;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Wraps a provider with the guards every free-tier call needs: a local throttle (so we do not burn the
 * provider's quota) and bounded retries with exponential backoff and jitter.
 * Streams are retried only until the first chunk has been delivered; after that the client has already
 * seen output, so the error is surfaced instead.
 */
public class ResilientProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientProviderClient.class);

    /** Injectable so tests do not really sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final ProviderClient delegate;
    private final ProviderProperties properties;
    private final ProviderThrottle throttle;
    private final MeterRegistry meterRegistry;
    private final Sleeper sleeper;

    public ResilientProviderClient(ProviderClient delegate, ProviderProperties properties,
                                   MeterRegistry meterRegistry) {
        this(delegate, properties, meterRegistry, duration -> Thread.sleep(duration));
    }

    ResilientProviderClient(ProviderClient delegate, ProviderProperties properties,
                            MeterRegistry meterRegistry, Sleeper sleeper) {
        this.delegate = delegate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.sleeper = sleeper;
        this.throttle = new ProviderThrottle(properties.requestsPerMinute(), properties.maxConcurrent(),
                properties.throttleMaxWait());
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean supports(String model) {
        return delegate.supports(model);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return withRetries(() -> throttled(() -> delegate.chat(request)), () -> false);
    }

    @Override
    public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        var delivered = new AtomicBoolean(false);
        Consumer<ChatChunk> tracking = chunk -> {
            delivered.set(true);
            onChunk.accept(chunk);
        };
        withRetries(() -> throttled(() -> {
            delegate.streamChat(request, tracking);
            return null;
        }), delivered::get);
    }

    private <T> T throttled(Supplier<T> call) {
        try (var ignored = throttle.acquire(name())) {
            return call.get();
        }
    }

    private <T> T withRetries(Supplier<T> call, Supplier<Boolean> alreadyDelivered) {
        int attempt = 1;
        while (true) {
            try {
                return call.get();
            } catch (GatewayException ex) {
                if (!ex.retryable() || attempt >= properties.maxAttempts() || alreadyDelivered.get()) {
                    throw ex;
                }
                var delay = delayFor(attempt, ex);
                if (delay == null) {
                    throw ex;
                }
                log.info("{} attempt {}/{} failed ({}); retrying in {} ms", name(), attempt,
                        properties.maxAttempts(), ex.code(), delay.toMillis());
                meterRegistry.counter("gateway.provider.retries", "provider", name()).increment();
                pause(delay);
                attempt++;
            }
        }
    }

    /** Null means "do not retry": the upstream asked us to wait longer than we are willing to. */
    private Duration delayFor(int attempt, GatewayException ex) {
        var cap = properties.maxBackoff();
        var exponential = properties.initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 20));
        var backoff = exponential.compareTo(cap) > 0 ? cap : exponential;
        // Equal jitter: spreads out concurrent retries so they do not stampede the provider.
        long half = backoff.toMillis() / 2;
        var jittered = Duration.ofMillis(half + (half > 0 ? ThreadLocalRandom.current().nextLong(half + 1) : 0));
        var hint = ex.retryAfter();
        if (hint == null) {
            return jittered;
        }
        if (hint.compareTo(cap) > 0) {
            return null;
        }
        return hint.compareTo(jittered) > 0 ? hint : jittered;
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "server_error",
                    "request_interrupted", "The request was interrupted");
        }
    }
}
