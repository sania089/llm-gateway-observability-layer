package com.gateway.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpStatus;

class OpenAiProviderClientTest {

    private static final String PATH = "/chat/completions";
    private static final String COMPLETION_JSON = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hi!"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""";
    private static final ChatRequest REQUEST = new ChatRequest(
            "gpt-4o-mini", List.of(new ChatMessage("user", "Hello")), null, null, null, null);

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private OpenAiProviderClient clientWithKey(String apiKey, Duration readTimeout) {
        return new OpenAiProviderClient(new OpenAiProperties(
                wireMock.baseUrl(), apiKey, Duration.ofSeconds(1), readTimeout, List.of("gpt-")));
    }

    private OpenAiProviderClient client() {
        return clientWithKey("sk-test", Duration.ofSeconds(2));
    }

    private void stubStatus(int status) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
    }

    private GatewayException failureOf(OpenAiProviderClient client) {
        return org.junit.jupiter.api.Assertions.assertThrows(GatewayException.class, () -> client.chat(REQUEST));
    }

    @Test
    void returnsCompletionAndSendsBearerKey() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(COMPLETION_JSON)));

        var response = client().chat(REQUEST);

        assertThat(response.choices().get(0).message().content()).isEqualTo("Hi!");
        assertThat(response.usage().totalTokens()).isEqualTo(7);
        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-test")));
    }

    @Test
    void supportsOnlyConfiguredModelPrefixes() {
        assertThat(client().supports("gpt-4o")).isTrue();
        assertThat(client().supports("claude-3")).isFalse();
    }

    @Test
    void mapsProvider429ToRateLimitError() {
        stubStatus(429);
        assertThat(failureOf(client()).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void mapsProvider5xxToBadGateway() {
        stubStatus(500);
        assertThat(failureOf(client()).status()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void mapsProvider401ToBadGatewayBecauseGatewayKeyIsAtFault() {
        stubStatus(401);
        assertThat(failureOf(client()).status()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void mapsProvider400ToBadRequest() {
        stubStatus(400);
        assertThat(failureOf(client()).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void mapsSlowProviderToGatewayTimeout() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFixedDelay(1500)));
        var slowClient = clientWithKey("sk-test", Duration.ofMillis(200));
        assertThat(failureOf(slowClient).status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void failsFastWhenApiKeyIsMissing() {
        assertThatThrownBy(() -> clientWithKey("", Duration.ofSeconds(1)).chat(REQUEST))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }
}
