package com.gateway.proxy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.common.error.GatewayException;
import com.gateway.provider.ChatChunk;
import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ChatController.class)
@Import(ChatStreamer.class)
@EnableConfigurationProperties(ProxyProperties.class)
@TestPropertySource(properties = "gateway.proxy.stream-timeout=5s")
class ChatControllerTest {

    private static final String STREAM_BODY = """
            {"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"Hello"}]}""";
    private static final ChatChunk TEXT_CHUNK = new ChatChunk("c1", ChatChunk.OBJECT_TYPE, 1L, "gpt-4o",
            List.of(new ChatChunk.Choice(0, new ChatChunk.Delta(null, "Hi"), null)), null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProxyService proxyService;

    private ResultActions postChat(String body) throws Exception {
        return mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions completeAsync(ResultActions started) throws Exception {
        MvcResult result = started.andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult();
        return mockMvc.perform(asyncDispatch(result));
    }

    @Test
    void returnsOpenAiShapedCompletion() throws Exception {
        var response = new ChatResponse("chatcmpl-1", "chat.completion", 1L, "gpt-4o",
                List.of(new ChatResponse.Choice(0, new ChatMessage("assistant", "Hi"), "stop")),
                new ChatResponse.Usage(1, 1, 2));
        when(proxyService.chat(any(ChatRequest.class))).thenReturn(response);

        postChat("""
                {"model":"gpt-4o","max_tokens":10,"messages":[{"role":"user","content":"Hello"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.total_tokens").value(2));
    }

    @Test
    void rejectsRequestWithoutMessagesUsingStandardErrorShape() throws Exception {
        postChat("""
                {"model":"gpt-4o"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void streamsChunksAsSseAndEndsWithDoneMarker() throws Exception {
        doAnswer(invocation -> {
            invocation.<Consumer<ChatChunk>>getArgument(1).accept(TEXT_CHUNK);
            return java.util.Optional.empty();
        }).when(proxyService).stream(any(ChatRequest.class), any());

        completeAsync(postChat(STREAM_BODY))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"content\":\"Hi\"")))
                .andExpect(content().string(org.hamcrest.Matchers.endsWith("data:[DONE]\n\n")));
    }

    @Test
    void answersWithRealHttpErrorWhenStreamFailsBeforeFirstChunk() throws Exception {
        doThrow(new GatewayException(HttpStatus.NOT_FOUND, "invalid_request_error", "model_not_found", "nope"))
                .when(proxyService).stream(any(ChatRequest.class), any());

        completeAsync(postChat(STREAM_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }
}
