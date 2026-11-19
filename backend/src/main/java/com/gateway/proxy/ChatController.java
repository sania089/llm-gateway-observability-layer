package com.gateway.proxy;

import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ProxyService proxyService;

    public ChatController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping("/v1/chat/completions")
    public ChatResponse createChatCompletion(@Valid @RequestBody ChatRequest request) {
        return proxyService.chat(request);
    }
}
