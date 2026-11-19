package com.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProxyServiceTest {

    private static final ChatResponse RESPONSE = new ChatResponse("id", "chat.completion", 1L, "gpt-4o", List.of(), null);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProviderClient fakeProvider = new ProviderClient() {
        @Override public String name() { return "fake"; }
        @Override public boolean supports(String model) { return model.startsWith("gpt-"); }
        @Override public ChatResponse chat(ChatRequest request) { return RESPONSE; }
    };
    private final ProxyService service = new ProxyService(List.of(fakeProvider), meterRegistry);

    private ChatRequest requestFor(String model, Boolean stream) {
        return new ChatRequest(model, List.of(new ChatMessage("user", "hi")), null, null, null, stream);
    }

    @Test
    void routesToProviderSupportingTheModelAndRecordsMetric() {
        assertThat(service.chat(requestFor("gpt-4o", null))).isSameAs(RESPONSE);
        assertThat(meterRegistry.get("gateway.provider.requests")
                .tag("provider", "fake").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownModelWith404() {
        assertThatThrownBy(() -> service.chat(requestFor("mystery-1", null)))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsStreamingUntilItIsSupported() {
        assertThatThrownBy(() -> service.chat(requestFor("gpt-4o", true)))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
