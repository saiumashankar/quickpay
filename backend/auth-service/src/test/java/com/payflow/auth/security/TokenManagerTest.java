package com.payflow.auth.security;

import com.payflow.auth.entity.Role;
import com.payflow.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * TokenManager is the contract that payment-service and notification-service depend on.
 * These tests pin the claim names, the token types and the lifetimes, because changing
 * any of them silently breaks both downstream services' JWT validation.
 */
class TokenManagerTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-for-hmac-sha256";
    private static final long FIFTEEN_MINUTES_MS = 900_000L;
    private static final long SEVEN_DAYS_MS = 604_800_000L;

    private final TokenManager tokenManager = new TokenManager(SECRET, FIFTEEN_MINUTES_MS, SEVEN_DAYS_MS);

    private User user() {
        return User.builder()
                .id(42L)
                .ownerId(UUID.fromString("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c"))
                .username("asha")
                .email("asha@payflow.dev")
                .password("{noop}secret")
                .role(Role.USER)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("the access token carries the identity every downstream service reads")
    void accessTokenCarriesIdentityClaims() {
        var claims = tokenManager.verifyAndExtractClaims(tokenManager.generateAccessToken(user()));

        assertThat(claims.getSubject()).isEqualTo("asha@payflow.dev");
        assertThat(claims.get("ownerId", String.class))
                .isEqualTo("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
        assertThat(claims.get("userId", Number.class).longValue()).isEqualTo(42L);
    }

    @Test
    @DisplayName("the refresh token is typed REFRESH so downstream services can refuse it")
    void refreshTokenIsTypedRefresh() {
        var claims = tokenManager.verifyAndExtractClaims(tokenManager.generateRefreshToken(user()));

        assertThat(claims.get("type", String.class)).isEqualTo("REFRESH");
    }

    @Test
    @DisplayName("an access token lives for 15 minutes and a refresh token for 7 days")
    void lifetimesAreDistinctAndShortForAccess() {
        var access = tokenManager.verifyAndExtractClaims(tokenManager.generateAccessToken(user()));
        var refresh = tokenManager.verifyAndExtractClaims(tokenManager.generateRefreshToken(user()));

        long accessSeconds = access.getExpiration().getTime() - access.getIssuedAt().getTime();
        long refreshSeconds = refresh.getExpiration().getTime() - refresh.getIssuedAt().getTime();

        assertThat(accessSeconds).isEqualTo(FIFTEEN_MINUTES_MS);
        assertThat(refreshSeconds).isEqualTo(SEVEN_DAYS_MS);
        assertThat(accessSeconds).isLessThan(refreshSeconds);
    }

    @Test
    @DisplayName("refresh validation refuses an access token, so /refresh cannot mint new tokens from one")
    void refreshValidationRejectsAccessToken() {
        String accessToken = tokenManager.generateAccessToken(user());

        assertThat(tokenManager.isTokenValid(accessToken, TokenManager.TokenType.REFRESH)).isFalse();
        assertThat(tokenManager.isTokenValid(accessToken, TokenManager.TokenType.ACCESS)).isTrue();
    }

    @Test
    @DisplayName("access validation refuses a refresh token, closing the reverse loophole")
    void accessValidationRejectsRefreshToken() {
        String refreshToken = tokenManager.generateRefreshToken(user());

        assertThat(tokenManager.isTokenValid(refreshToken, TokenManager.TokenType.ACCESS)).isFalse();
    }

    @Test
    @DisplayName("a token signed with a different secret is not trusted")
    void foreignSignatureIsRejected() {
        String foreign = new TokenManager("a-completely-different-secret-of-sufficient-length",
                FIFTEEN_MINUTES_MS, SEVEN_DAYS_MS).generateAccessToken(user());

        assertThat(tokenManager.isTokenValid(foreign, TokenManager.TokenType.ACCESS)).isFalse();
        assertThatThrownBy(() -> tokenManager.extractUsername(foreign))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a tampered payload invalidates the signature")
    void tamperedTokenIsRejected() {
        String token = tokenManager.generateAccessToken(user());
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAAdefinitelyNotAValidSignature";

        assertThat(tokenManager.isTokenValid(tampered, TokenManager.TokenType.ACCESS)).isFalse();
    }

    @Test
    @DisplayName("a garbage string is rejected rather than throwing to the caller")
    void garbageTokenIsRejected() {
        assertThat(tokenManager.isTokenValid("not-a-token", TokenManager.TokenType.ACCESS)).isFalse();
        assertThat(tokenManager.isTokenValid("", TokenManager.TokenType.ACCESS)).isFalse();
    }

    @Test
    @DisplayName("the subject is recoverable, which is how refresh looks the user up")
    void subjectIsExtractable() {
        assertThat(tokenManager.extractUsername(tokenManager.generateAccessToken(user())))
                .isEqualTo("asha@payflow.dev");
    }

    @Test
    @DisplayName("the expiry returned to the client matches the token's own claim")
    void reportedExpiryMatchesToken() {
        Instant reported = tokenManager.getExpiration(tokenManager.generateAccessToken(user()));
        Instant claimed = tokenManager.verifyAndExtractClaims(tokenManager.generateAccessToken(user()))
                .getExpiration().toInstant();

        assertThat(reported).isCloseTo(claimed, within(2, ChronoUnit.SECONDS));
    }
}
