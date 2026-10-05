package com.payflow.payment.security;

import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.exception.PaymentForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserProviderTest {

    private final AuthenticatedUserProvider provider = new AuthenticatedUserProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthenticationToken authenticate(Map<String, Object> claims, String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
        claims.forEach(builder::claim);
        Authentication authentication = new JwtAuthenticationToken(builder.build(),
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return (JwtAuthenticationToken) authentication;
    }

    @Test
    @DisplayName("identity is read from verified claims, never from request input")
    void identityComesFromTokenClaims() {
        authenticate(Map.of(
                "userId", 42,
                "ownerId", "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c",
                "role", "USER"), "asha@payflow.dev");

        GatewayUserContext context = provider.currentUser();

        assertThat(context.userId()).isEqualTo(42L);
        assertThat(context.ownerId()).isEqualTo("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c");
        assertThat(context.role()).isEqualTo("USER");
        assertThat(context.email()).isEqualTo("asha@payflow.dev");
    }

    @Test
    @DisplayName("a userId sent as a string is still accepted, since JWT claims are JSON")
    void stringUserIdClaimIsCoerced() {
        authenticate(Map.of(
                "userId", "42",
                "ownerId", "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c",
                "role", "USER"), "asha@payflow.dev");

        assertThat(provider.currentUser().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a token with no userId claim is rejected instead of defaulting to a value")
    void missingUserIdClaimIsRejected() {
        authenticate(Map.of("ownerId", "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c", "role", "USER"),
                "asha@payflow.dev");

        assertThatThrownBy(() -> provider.currentUser())
                .isInstanceOf(PaymentForbiddenException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("a non numeric userId claim is rejected")
    void nonNumericUserIdClaimIsRejected() {
        authenticate(Map.of(
                "userId", "not-a-number",
                "ownerId", "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c",
                "role", "USER"), "asha@payflow.dev");

        assertThatThrownBy(() -> provider.currentUser())
                .isInstanceOf(PaymentForbiddenException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("an empty security context is rejected rather than treated as anonymous access")
    void emptyContextIsRejected() {
        assertThatThrownBy(() -> provider.currentUser())
                .isInstanceOf(PaymentForbiddenException.class)
                .hasMessageContaining("security context");
    }

    @Test
    @DisplayName("a non JWT authentication, such as a session login, is rejected")
    void nonJwtAuthenticationIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("asha@payflow.dev", "password",
                        List.of()));

        assertThatThrownBy(() -> provider.currentUser())
                .isInstanceOf(PaymentForbiddenException.class)
                .hasMessageContaining("security context");
    }
}
