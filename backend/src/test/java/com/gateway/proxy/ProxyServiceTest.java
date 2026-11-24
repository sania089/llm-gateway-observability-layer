package com.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProxyServiceTest {

    private static final ChatResponse RESPONSE = new ChatResponse("id", "chat.completion", 1L, "gpt-4o", List.of(), null);
    private static final ChatResponse.Usage USAGE = new ChatResponse.Usage(3, 4, 7);
    private static final ChatChunk TEXT_CHUNK = new ChatChunk("id", ChatChunk.OBJECT_TYPE, 1L, "gpt-4o",
            List.of(new ChatChunk.Choice(0, new ChatChunk.Delta(null, "Hi"), null)), null);
    private static final ChatChunk USAGE_CHUNK = new ChatChunk("id", ChatChunk.OBJECT_TYPE, 1L, "gpt-4o",
            List.of(), USAGE);

    private static final ProxyProperties PROPERTIES = new ProxyProperties(java.time.Duration.ofMinutes(1), 50, 100);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProviderClient fakeProvider = new ProviderClient() {
        @Override public String name() { return "fake"; }
        @Override public boolean supports(String model) { return model.startsWith("gpt-"); }
        @Override public ChatResponse chat(ChatRequest request) { return RESPONSE; }
        @Override public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
            onChunk.accept(TEXT_CHUNK);
            onChunk.accept(USAGE_CHUNK);
        }
    };
    private final ProxyService service = new ProxyService(List.of(fakeProvider), meterRegistry, PROPERTIES);

    private ChatRequest requestFor(String model) {
        return new ChatRequest(model, List.of(new ChatMessage("user", "hi")), null, null, null, null, null);
    }

    @Test
    void routesToProviderSupportingTheModelAndRecordsMetric() {
        assertThat(service.chat(requestFor("gpt-4o"))).isSameAs(RESPONSE);
        assertThat(meterRegistry.get("gateway.provider.requests")
                .tag("provider", "fake").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownModelWith404() {
        assertThatThrownBy(() -> service.chat(requestFor("mystery-1")))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void streamsChunksAndReturnsUsageFromTheFinalChunk() {
        var received = new ArrayList<ChatChunk>();

        var usage = service.stream(requestFor("gpt-4o"), received::add);

        assertThat(received).containsExactly(TEXT_CHUNK, USAGE_CHUNK);
        assertThat(usage).contains(USAGE);
        assertThat(meterRegistry.get("gateway.provider.requests")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void returnsNoUsageWhenStreamEndsWithoutOne() {
        var provider = new ProviderClient() {
            @Override public String name() { return "quiet"; }
            @Override public boolean supports(String model) { return true; }
            @Override public ChatResponse chat(ChatRequest request) { return RESPONSE; }
            @Override public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
                onChunk.accept(TEXT_CHUNK);
            }
        };

        var usage = new ProxyService(List.of(provider), meterRegistry, PROPERTIES)
                .stream(requestFor("any"), chunk -> { });

        assertThat(usage).isEmpty();
    }

    @Test
    void recordsErrorOutcomeWhenStreamFails() {
        Consumer<ChatChunk> failingSink = chunk -> { throw new IllegalStateException("client gone"); };

        assertThatThrownBy(() -> service.stream(requestFor("gpt-4o"), failingSink))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("gateway.provider.requests")
                .tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void rejectsPromptsOverTheInputLimitWith413() {
        var big = new ChatRequest("gpt-4o", List.of(new ChatMessage("user", "x".repeat(51))),
                null, null, null, null, null);

        assertThatThrownBy(() -> service.chat(big))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void rejectsNonPositiveMaxTokens() {
        var request = requestFor("gpt-4o").withMaxTokens(0);

        assertThatThrownBy(() -> service.chat(request))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void capsOrDefaultsMaxTokensBeforeCallingTheProvider() {
        var seen = new ArrayList<Integer>();
        var provider = new ProviderClient() {
            @Override public String name() { return "spy"; }
            @Override public boolean supports(String model) { return true; }
            @Override public ChatResponse chat(ChatRequest request) {
                seen.add(request.maxTokens());
                return RESPONSE;
            }
            @Override public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) { }
        };
        var spyService = new ProxyService(List.of(provider), meterRegistry, PROPERTIES);

        spyService.chat(requestFor("any"));
        spyService.chat(requestFor("any").withMaxTokens(5000));
        spyService.chat(requestFor("any").withMaxTokens(10));

        assertThat(seen).containsExactly(100, 100, 10);
    }
}
