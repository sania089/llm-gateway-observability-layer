package com.gateway.proxy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.provider.ChatMessage;
import com.gateway.provider.ChatRequest;
import com.gateway.provider.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProxyService proxyService;

    private org.springframework.test.web.servlet.ResultActions postChat(String body) throws Exception {
        return mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body));
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
}
