package com.gateway.provider;

import java.util.function.Consumer;

/** Extension point: one implementation per upstream LLM provider. */
public interface ProviderClient {

    /** Short, low-cardinality provider name used in metrics and logs. */
    String name();

    boolean supports(String model);

    ChatResponse chat(ChatRequest request);

    /**
     * Calls the provider in streaming mode and hands each OpenAI-compatible chunk to {@code onChunk}.
     * The last chunk carries token usage. If {@code onChunk} throws, the upstream call is aborted.
     */
    void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk);
}
