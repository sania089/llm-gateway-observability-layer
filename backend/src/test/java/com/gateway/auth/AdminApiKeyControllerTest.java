package com.gateway.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(AdminApiKeyController.class)
@Import(AdminAuthorizer.class)
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = "gateway.admin.key=admin-secret")
class AdminApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    private ResultActions createKey(String authorization, String body) throws Exception {
        var request = post("/admin/v1/api-keys").contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mockMvc.perform(request);
    }

    @Test
    void createsKeyAndReturnsRawKeyOnce() throws Exception {
        when(apiKeyService.create("billing")).thenReturn(
                new IssuedApiKey(UUID.randomUUID(), "billing", "gw-abcde", "gw-abcdefghijk", Instant.now()));

        createKey("Bearer admin-secret", "{\"owner\":\"billing\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("gw-abcdefghijk"))
                .andExpect(jsonPath("$.key_prefix").value("gw-abcde"));
    }

    @Test
    void rejectsWrongAdminKey() throws Exception {
        createKey("Bearer wrong", "{\"owner\":\"billing\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_admin_key"));
    }

    @Test
    void rejectsMissingAdminKey() throws Exception {
        createKey(null, "{\"owner\":\"billing\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBlankOwner() throws Exception {
        createKey("Bearer admin-secret", "{\"owner\":\" \"}").andExpect(status().isBadRequest());
    }
}
