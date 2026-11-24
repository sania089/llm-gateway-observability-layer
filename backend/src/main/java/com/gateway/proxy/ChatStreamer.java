package com.gateway.proxy;

import com.gateway.common.error.ApiError;
import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bridges a provider stream to the client as OpenAI-style SSE, one virtual thread per stream. */
@Component
class ChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamer.class);
    private static final String DONE_MARKER = "[DONE]";

    private final ProxyService proxyService;
    private final ProxyProperties properties;

    ChatStreamer(ProxyService proxyService, ProxyProperties properties) {
        this.proxyService = proxyService;
        this.properties = properties;
    }

    SseEmitter open(ChatRequest request) {
        var emitter = new SseEmitter(properties.streamTimeout().toMillis());
        Thread.startVirtualThread(() -> run(request, emitter));
        return emitter;
    }

    private void run(ChatRequest request, SseEmitter emitter) {
        var started = new AtomicBoolean(false);
        try {
            proxyService.stream(request, chunk -> {
                send(emitter, chunk, MediaType.APPLICATION_JSON);
                started.set(true);
            });
            send(emitter, DONE_MARKER, MediaType.TEXT_PLAIN);
            emitter.complete();
        } catch (ClientDisconnectedException ex) {
            log.debug("Client disconnected mid-stream; upstream call aborted");
        } catch (GatewayException ex) {
            fail(emitter, ex, started.get());
        } catch (RuntimeException ex) {
            log.error("Unexpected failure while streaming", ex);
            fail(emitter, new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "internal_error",
                    "Internal server error"), started.get());
        }
    }

    // Before the first chunk the HTTP status is still open, so the standard error handler can answer.
    // After that the status is already 200, so the error travels as an SSE event instead.
    private void fail(SseEmitter emitter, GatewayException ex, boolean alreadyStreaming) {
        if (!alreadyStreaming) {
            emitter.completeWithError(ex);
            return;
        }
        try {
            send(emitter, ApiError.of(ex.getMessage(), ex.type(), ex.code()), MediaType.APPLICATION_JSON);
            emitter.complete();
        } catch (ClientDisconnectedException ignored) {
            log.debug("Client disconnected before the stream error could be delivered");
        }
    }

    private void send(SseEmitter emitter, Object data, MediaType mediaType) {
        try {
            emitter.send(SseEmitter.event().data(data, mediaType));
        } catch (IOException | IllegalStateException ex) {
            throw new ClientDisconnectedException(ex);
        }
    }
}
