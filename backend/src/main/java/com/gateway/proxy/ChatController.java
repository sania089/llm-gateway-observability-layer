package com.gateway.proxy;

import com.gateway.provider.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ProxyService proxyService;
    private final ChatStreamer chatStreamer;

    public ChatController(ProxyService proxyService, ChatStreamer chatStreamer) {
        this.proxyService = proxyService;
        this.chatStreamer = chatStreamer;
    }

    /** Returns a ChatResponse, or an SseEmitter when {@code stream: true}; hence the Object type. */
    @PostMapping("/v1/chat/completions")
    public Object createChatCompletion(@Valid @RequestBody ChatRequest request) {
        return request.isStreaming() ? chatStreamer.open(request) : proxyService.chat(request);
    }
}
