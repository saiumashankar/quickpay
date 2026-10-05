package com.payflow.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards /api/internal/** with a shared secret rather than a user JWT.
 *
 * This is deliberately not an OAuth2 client_credentials flow. notification-service
 * acts on behalf of no user, so there is no user token to forward: the event
 * carries no credentials, and putting one on the topic would leak a token that
 * is valid everywhere for 15 minutes.
 *
 * The trade-off is a shared symmetric secret. It is readable by anything that
 * can already talk to auth-service, it is not scoped per caller, and rotating
 * it requires redeploying every caller. A real deployment would replace this
 * with client_credentials or mTLS.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    private final String expectedToken;

    public InternalTokenFilter(@Value("${payflow.internal.api-token:}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // An unset token disables the internal surface entirely rather than
        // letting it fall open, so a misconfigured deploy fails closed.
        if (expectedToken.isBlank() || !constantTimeEquals(request.getHeader(INTERNAL_TOKEN_HEADER), expectedToken)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"internal endpoint requires a valid X-Internal-Token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * MessageDigest.isEqual is used rather than String.equals because equals
     * short-circuits on the first differing character and leaks the length of
     * the matching prefix through timing.
     */
    private static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
