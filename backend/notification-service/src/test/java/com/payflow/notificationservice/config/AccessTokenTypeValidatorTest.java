package com.payflow.notificationservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenTypeValidatorTest {

    private final AccessTokenTypeValidator validator = new AccessTokenTypeValidator();

    private Jwt withType(String type) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("asha@payflow.dev")
                .claim("type", type)
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    @DisplayName("an access token is accepted")
    void accessTokenIsAccepted() {
        assertThat(validator.validate(withType("ACCESS")).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("a refresh token is rejected, so the long lived credential cannot call the API")
    void refreshTokenIsRejected() {
        assertThat(validator.validate(withType("REFRESH")).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token with no type claim is rejected")
    void missingTypeClaimIsRejected() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("asha@payflow.dev")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("the type comparison is case sensitive")
    void typeComparisonIsCaseSensitive() {
        assertThat(validator.validate(withType("access")).hasErrors()).isTrue();
    }
}
