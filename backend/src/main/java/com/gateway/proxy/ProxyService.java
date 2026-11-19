package com.gateway.proxy;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import com.gateway.provider.ProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProxyService {

    private static final String METRIC_NAME = "gateway.provider.requests";

    private final List<ProviderClient> providers;
    private final MeterRegistry meterRegistry;

    public ProxyService(List<ProviderClient> providers, MeterRegistry meterRegistry) {
        this.providers = List.copyOf(providers);
        this.meterRegistry = meterRegistry;
    }

    public ChatResponse chat(ChatRequest request) {
        if (request.isStreaming()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error",
                    "streaming_not_supported", "Streaming is not supported yet");
        }
        return call(findProvider(request.model()), request);
    }

    private ProviderClient findProvider(String model) {
        return providers.stream()
                .filter(provider -> provider.supports(model))
                .findFirst()
                .orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "invalid_request_error",
                        "model_not_found", "The model '" + model + "' is not supported"));
    }

    private ChatResponse call(ProviderClient provider, ChatRequest request) {
        var sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            var response = provider.chat(request);
            outcome = "success";
            return response;
        } finally {
            sample.stop(Timer.builder(METRIC_NAME)
                    .tag("provider", provider.name())
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}
