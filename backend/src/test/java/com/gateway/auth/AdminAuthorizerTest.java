package com.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.common.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminAuthorizerTest {

    @Test
    void failsClosedWith403WhenNoAdminKeyIsConfigured() {
        var authorizer = new AdminAuthorizer(new AdminProperties(""));

        assertThatThrownBy(() -> authorizer.requireAdmin("Bearer anything"))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void acceptsOnlyTheExactAdminKey() {
        var authorizer = new AdminAuthorizer(new AdminProperties("secret"));

        authorizer.requireAdmin("Bearer secret");
        assertThatThrownBy(() -> authorizer.requireAdmin("Bearer secre"))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> authorizer.requireAdmin("secret")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> authorizer.requireAdmin(null)).isInstanceOf(GatewayException.class);
    }
}
