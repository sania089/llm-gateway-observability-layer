package com.gateway.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/api-keys")
class AdminApiKeyController {

    private static final int MAX_OWNER_LENGTH = 100;

    private final AdminAuthorizer adminAuthorizer;
    private final ApiKeyService apiKeyService;

    AdminApiKeyController(AdminAuthorizer adminAuthorizer, ApiKeyService apiKeyService) {
        this.adminAuthorizer = adminAuthorizer;
        this.apiKeyService = apiKeyService;
    }

    record CreateApiKeyRequest(@NotBlank @Size(max = MAX_OWNER_LENGTH) String owner) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IssuedApiKey create(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                        @Valid @RequestBody CreateApiKeyRequest request) {
        adminAuthorizer.requireAdmin(authorization);
        return apiKeyService.create(request.owner());
    }
}
