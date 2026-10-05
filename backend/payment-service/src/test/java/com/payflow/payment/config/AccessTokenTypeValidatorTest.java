package com.payflow.payment.config;

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
                .claim("type", type)
                .claim("sub", "asha@payflow.dev")
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
    @DisplayName("a refresh token is rejected")
    void refreshTokenIsRejected() {
        assertThat(validator.validate(withType("REFRESH")).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token with no type claim is rejected")
    void missingTypeClaimIsRejected() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("asha@payflow.dev")
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        assertThat(token.getClaimAsString("type")).isNull();
        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("the type check is case sensitive, so a lowercase claim is not accepted")
    void typeComparisonIsCaseSensitive() {
        assertThat(validator.validate(withType("access")).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("an attacker cannot satisfy the check by sending the type as a non string claim")
    void nonStringTypeClaimIsRejected() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("asha@payflow.dev")
                .claim("type", 42)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }
}
