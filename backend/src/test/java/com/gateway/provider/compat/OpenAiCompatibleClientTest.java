package com.gateway.provider.compat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpStatus;

class OpenAiCompatibleClientTest {

    private static final String PATH = "/chat/completions";
    private static final String COMPLETION_JSON = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hi!"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""";
    private static final String STREAM_BODY = """
            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hi"}}]}

            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

            data: [DONE]

            """;
    private static final ChatRequest REQUEST = new ChatRequest(
            "gpt-4o-mini", List.of(new ChatMessage("user", "Hello")), null, null, null, null, null);

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private OpenAiCompatibleClient clientWithKey(String apiKey, Duration readTimeout) {
        return new OpenAiCompatibleClient("compat-test", new ProviderProperties(
                wireMock.baseUrl(), apiKey, Duration.ofSeconds(1), readTimeout, List.of("gpt-")),
                new ObjectMapper());
    }

    private OpenAiCompatibleClient client() {
        return clientWithKey("sk-test", Duration.ofSeconds(2));
    }

    private void stubStatus(int status) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
    }

    private GatewayException failureOf(OpenAiCompatibleClient client) {
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
    void forwardsStreamChunksSkipsDoneMarkerAndRequestsUsage() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody(STREAM_BODY)));
        var chunks = new java.util.ArrayList<ChatChunk>();

        client().streamChat(REQUEST, chunks::add);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("Hi");
        assertThat(chunks.get(1).usage().totalTokens()).isEqualTo(7);
        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));
    }

    @Test
    void abortsStreamWhenConsumerThrows() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody(STREAM_BODY)));
        var received = new java.util.ArrayList<ChatChunk>();

        assertThatThrownBy(() -> client().streamChat(REQUEST, chunk -> {
            received.add(chunk);
            throw new IllegalStateException("client gone");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(received).hasSize(1);
    }

    @Test
    void mapsStreamingProvider429ToRateLimitError() {
        stubStatus(429);
        assertThatThrownBy(() -> client().streamChat(REQUEST, chunk -> { }))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void failsFastWhenApiKeyIsMissing() {
        assertThatThrownBy(() -> clientWithKey("", Duration.ofSeconds(1)).chat(REQUEST))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void marksRateLimitAndServerErrorsRetryableButNotClientErrors() {
        stubStatus(429);
        assertThat(failureOf(client()).retryable()).isTrue();
        stubStatus(503);
        assertThat(failureOf(client()).retryable()).isTrue();
        stubStatus(400);
        assertThat(failureOf(client()).retryable()).isFalse();
        stubStatus(401);
        assertThat(failureOf(client()).retryable()).isFalse();
    }

    @Test
    void marksTimeoutsRetryable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFixedDelay(1500)));
        assertThat(failureOf(clientWithKey("sk-test", Duration.ofMillis(200))).retryable()).isTrue();
    }

    @Test
    void parsesRetryAfterSecondsFrom429() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));
        assertThat(failureOf(client()).retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void parsesRetryAfterOnStreamingErrorsToo() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3")));
        assertThatThrownBy(() -> client().streamChat(REQUEST, chunk -> { }))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(3)));
    }
}
