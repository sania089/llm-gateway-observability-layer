package com.gateway.provider.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import com.gateway.provider.ProviderHttp;
import com.gateway.provider.SseDataReader;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Client for any provider that speaks the OpenAI chat completions protocol (OpenAI, Gemini, Groq...). */
public class OpenAiCompatibleClient implements ProviderClient {

    private static final String DONE_MARKER = "[DONE]";

    private final String name;
    private final ProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleClient(String name, ProviderProperties properties, ObjectMapper objectMapper) {
        this.name = name;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = ProviderHttp.restClient(
                properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(String model) {
        return properties.modelPrefixes().stream().anyMatch(model::startsWith);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ProviderHttp.requireApiKey(name, properties.apiKey());
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientResponseException ex) {
            throw ProviderHttp.failureForStatus(name, ex.getStatusCode().value(), ex.getResponseHeaders());
        } catch (ResourceAccessException ex) {
            throw ProviderHttp.failureForTransport(name, ex);
        }
    }

    @Override
    public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        ProviderHttp.requireApiKey(name, properties.apiKey());
        try {
            restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(request.withUsageInStream())
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            throw ProviderHttp.failureForStatus(name, res.getStatusCode().value(), res.getHeaders());
                        }
                        SseDataReader.forEachData(res.getBody(), data -> forwardChunk(data, onChunk));
                        return null;
                    });
        } catch (ResourceAccessException ex) {
            throw ProviderHttp.failureForTransport(name, ex);
        }
    }

    private void forwardChunk(String data, Consumer<ChatChunk> onChunk) {
        if (DONE_MARKER.equals(data)) {
            return;
        }
        try {
            onChunk.accept(objectMapper.readValue(data, ChatChunk.class));
        } catch (JsonProcessingException ex) {
            throw ProviderHttp.malformedStream();
        }
    }
}
