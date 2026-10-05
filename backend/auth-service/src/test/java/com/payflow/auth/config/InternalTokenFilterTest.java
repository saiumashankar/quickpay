package com.payflow.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenFilterTest {

    private static final String SECRET = "internal-secret";

    private MockHttpServletResponse invoke(String configuredToken, String requestPath, String providedToken)
            throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(configuredToken);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestPath);
        if (providedToken != null) {
            request.addHeader(InternalTokenFilter.INTERNAL_TOKEN_HEADER, providedToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("a request with the right token reaches the endpoint")
    void validTokenPasses() throws Exception {
        MockHttpServletResponse response = invoke(SECRET, "/api/internal/users/x", SECRET);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a request with no token is rejected")
    void missingTokenIsRejected() throws Exception {
        MockHttpServletResponse response = invoke(SECRET, "/api/internal/users/x", null);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a request with the wrong token is rejected")
    void wrongTokenIsRejected() throws Exception {
        MockHttpServletResponse response = invoke(SECRET, "/api/internal/users/x", "guessed");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a token that is a prefix of the real one is rejected")
    void prefixOfTokenIsRejected() throws Exception {
        MockHttpServletResponse response = invoke(SECRET, "/api/internal/users/x", SECRET.substring(0, 5));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("an unconfigured token disables the internal surface rather than opening it")
    void blankConfiguredTokenRejectsEverything() throws Exception {
        MockHttpServletResponse response = invoke("", "/api/internal/users/x", "");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the filter ignores paths outside the internal surface")
    void nonInternalPathIsNotFiltered() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
