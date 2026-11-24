package com.gateway.provider.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ResilientProviderClientTest {

    private static final ChatRequest REQUEST = new ChatRequest(
            "gemini-2.0-flash", List.of(new ChatMessage("user", "hi")), null, null, null, null, null);
    private static final ChatResponse OK = new ChatResponse("id", "chat.completion", 1L, "m", List.of(), null);
    private static final ChatChunk CHUNK = new ChatChunk("c", ChatChunk.OBJECT_TYPE, 1L, "m", List.of(), null);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Duration> sleeps = new ArrayList<>();

    private static GatewayException transientFailure(Duration retryAfter) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "provider_rate_limited",
                "limited", true, retryAfter);
    }

    private static GatewayException permanentFailure() {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error", "provider_rejected_request", "bad");
    }

    private ProviderProperties props(int attempts, int rpm, int concurrent, Duration maxWait) {
        return new ProviderProperties("http://x", "k", Duration.ofSeconds(1), Duration.ofSeconds(1),
                List.of("gemini-"), attempts, Duration.ofMillis(100), Duration.ofSeconds(2), rpm, concurrent, maxWait);
    }

    private ResilientProviderClient wrap(ProviderClient delegate, ProviderProperties properties) {
        return new ResilientProviderClient(delegate, properties, meters, sleeps::add);
    }

    /** Fails with the given exceptions in order, then succeeds. */
    private static final class Scripted implements ProviderClient {
        private final List<GatewayException> failures;
        private final boolean emitChunkBeforeFailing;
        int calls;

        Scripted(boolean emitChunkBeforeFailing, GatewayException... failures) {
            this.failures = List.of(failures);
            this.emitChunkBeforeFailing = emitChunkBeforeFailing;
        }

        @Override public String name() { return "scripted"; }
        @Override public boolean supports(String model) { return true; }

        @Override public ChatResponse chat(ChatRequest request) {
            if (calls < failures.size()) {
                throw failures.get(calls++);
            }
            calls++;
            return OK;
        }

        @Override public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
            if (calls < failures.size()) {
                if (emitChunkBeforeFailing) {
                    onChunk.accept(CHUNK);
                }
                throw failures.get(calls++);
            }
            calls++;
            onChunk.accept(CHUNK);
        }
    }

    @Test
    void retriesTransientFailuresThenSucceeds() {
        var delegate = new Scripted(false, transientFailure(null), transientFailure(null));

        var response = wrap(delegate, props(3, 0, 0, null)).chat(REQUEST);

        assertThat(response).isSameAs(OK);
        assertThat(delegate.calls).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
        assertThat(meters.get("gateway.provider.retries").tag("provider", "scripted").counter().count()).isEqualTo(2);
    }

    @Test
    void stopsAfterMaxAttemptsAndSurfacesTheLastError() {
        var delegate = new Scripted(false, transientFailure(null), transientFailure(null), transientFailure(null));

        assertThatThrownBy(() -> wrap(delegate, props(3, 0, 0, null)).chat(REQUEST))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(delegate.calls).isEqualTo(3);
    }

    @Test
    void neverRetriesClientErrors() {
        var delegate = new Scripted(false, permanentFailure());

        assertThatThrownBy(() -> wrap(delegate, props(3, 0, 0, null)).chat(REQUEST))
                .isInstanceOf(GatewayException.class);

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void honoursRetryAfterWhenWithinTheBackoffCap() {
        var delegate = new Scripted(false, transientFailure(Duration.ofSeconds(1)));

        wrap(delegate, props(2, 0, 0, null)).chat(REQUEST);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void givesUpImmediatelyWhenRetryAfterExceedsTheCap() {
        var delegate = new Scripted(false, transientFailure(Duration.ofSeconds(30)));

        assertThatThrownBy(() -> wrap(delegate, props(3, 0, 0, null)).chat(REQUEST))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(30)));

        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void retriesStreamsThatFailBeforeTheFirstChunk() {
        var delegate = new Scripted(false, transientFailure(null));
        var received = new ArrayList<ChatChunk>();

        wrap(delegate, props(3, 0, 0, null)).streamChat(REQUEST, received::add);

        assertThat(received).hasSize(1);
        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void doesNotRetryStreamsOnceOutputHasReachedTheClient() {
        var delegate = new Scripted(true, transientFailure(null));
        var received = new ArrayList<ChatChunk>();

        assertThatThrownBy(() -> wrap(delegate, props(3, 0, 0, null)).streamChat(REQUEST, received::add))
                .isInstanceOf(GatewayException.class);

        assertThat(received).hasSize(1);
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void throttleRejectsWith429AndRetryAfterWhenRequestsPerMinuteIsExhausted() {
        var delegate = new Scripted(false);
        var client = wrap(delegate, props(1, 2, 0, Duration.ofMillis(50)));

        client.chat(REQUEST);
        client.chat(REQUEST);

        assertThatThrownBy(() -> client.chat(REQUEST))
                .isInstanceOfSatisfying(GatewayException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.code()).isEqualTo("provider_quota_protected");
                    assertThat(ex.retryAfter()).isPositive();
                });
        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void throttleCapsConcurrentCalls() throws Exception {
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var slow = new ProviderClient() {
            @Override public String name() { return "slow"; }
            @Override public boolean supports(String model) { return true; }
            @Override public ChatResponse chat(ChatRequest request) {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return OK;
            }
            @Override public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) { }
        };
        var client = wrap(slow, props(1, 0, 2, Duration.ofSeconds(5)));

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> client.chat(REQUEST)));
            }
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }
}
