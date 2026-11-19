package com.gateway.provider;

/** Extension point: one implementation per upstream LLM provider. */
public interface ProviderClient {

    /** Short, low-cardinality provider name used in metrics and logs. */
    String name();

    boolean supports(String model);

    ChatResponse chat(ChatRequest request);
}
