package com.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApiKeyServiceTest {

    // SHA-256 of "abc", a well-known test vector.
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyService service = new ApiKeyService(repository);

    @Test
    void hashesWithSha256() {
        assertThat(ApiKeyService.sha256Hex("abc")).isEqualTo(ABC_SHA256);
    }

    @Test
    void createsGatewayPrefixedKeyAndStoresOnlyItsHash() {
        var issued = service.create("billing-service");

        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(eq(issued.id()), hash.capture(), eq(issued.keyPrefix()), eq("billing-service"),
                eq(issued.createdAt()));
        assertThat(issued.key()).startsWith("gw-");
        assertThat(hash.getValue()).isEqualTo(ApiKeyService.sha256Hex(issued.key())).isNotEqualTo(issued.key());
        assertThat(issued.key()).startsWith(issued.keyPrefix());
    }

    @Test
    void generatesDifferentKeysEachTime() {
        assertThat(service.create("a").key()).isNotEqualTo(service.create("a").key());
    }

    @Test
    void authenticatesByLookingUpTheHashOfThePresentedKey() {
        var principal = new ApiKeyPrincipal(UUID.randomUUID(), "owner");
        when(repository.findActiveByHash(ABC_SHA256)).thenReturn(Optional.of(principal));

        assertThat(service.authenticate("abc")).contains(principal);
        assertThat(service.authenticate("other")).isEmpty();
    }
}
