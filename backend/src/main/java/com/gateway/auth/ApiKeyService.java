package com.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "gw-";
    private static final int RANDOM_BYTES = 32;
    private static final int DISPLAY_PREFIX_LENGTH = 8;

    private final ApiKeyRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    public IssuedApiKey create(String owner) {
        String rawKey = KEY_PREFIX + randomToken();
        var id = UUID.randomUUID();
        var createdAt = Instant.now();
        String displayPrefix = rawKey.substring(0, DISPLAY_PREFIX_LENGTH);
        repository.insert(id, sha256Hex(rawKey), displayPrefix, owner, createdAt);
        return new IssuedApiKey(id, owner, displayPrefix, rawKey, createdAt);
    }

    /** Keys are 256-bit random values, so a plain hash lookup is safe (no salting or slow hashing needed). */
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        return repository.findActiveByHash(sha256Hex(rawKey));
    }

    private String randomToken() {
        var bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }
}
