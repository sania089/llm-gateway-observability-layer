package com.gateway.provider.openai;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderClient.class);
    private static final String NAME = "openai";
    private static final String UPSTREAM_ERROR = "upstream_error";

    private final OpenAiProperties properties;
    private final RestClient restClient;

    public OpenAiProviderClient(OpenAiProperties properties) {
        this.properties = properties;
        this.restClient = buildRestClient(properties);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(String model) {
        return properties.modelPrefixes().stream().anyMatch(model::startsWith);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requireApiKey();
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientResponseException ex) {
            throw mapUpstreamStatus(ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            throw mapTransportFailure(ex);
        }
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "server_error",
                    "provider_not_configured", "OpenAI provider is not configured");
        }
    }

    // Provider messages are not forwarded: they can echo request content or reveal account details.
    private GatewayException mapUpstreamStatus(int status) {
        log.warn("OpenAI returned status {}", status);
        return switch (status) {
            case 400, 404, 422 -> new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error",
                    "provider_rejected_request", "The provider rejected the request");
            case 429 -> new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error",
                    "provider_rate_limited", "The provider is rate limiting requests");
            default -> new GatewayException(HttpStatus.BAD_GATEWAY, "server_error",
                    UPSTREAM_ERROR, "The provider returned an error");
        };
    }

    private GatewayException mapTransportFailure(ResourceAccessException ex) {
        boolean timedOut = ex.getCause() instanceof java.net.http.HttpTimeoutException
                || ex.getCause() instanceof java.net.SocketTimeoutException;
        log.warn("OpenAI call failed: {}", timedOut ? "timeout" : "connection error");
        return timedOut
                ? new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "server_error",
                        "upstream_timeout", "The provider timed out")
                : new GatewayException(HttpStatus.BAD_GATEWAY, "server_error",
                        UPSTREAM_ERROR, "Could not reach the provider");
    }

    private static RestClient buildRestClient(OpenAiProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
