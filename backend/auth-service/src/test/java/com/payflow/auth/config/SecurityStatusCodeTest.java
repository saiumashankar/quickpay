package com.payflow.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * These are status codes a client branches on, so they are pinned here rather
 * than left to whatever Spring Security happens to default to.
 */
@SpringBootTest(properties = "payflow.internal.api-token=test-internal-token")
@AutoConfigureMockMvc
class SecurityStatusCodeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Spring answers an unauthenticated request to an .authenticated() rule with
     * 403 by default, which asserts the caller WAS authenticated and is simply
     * not allowed. Nothing about this request identifies anyone, so 401 is the
     * honest answer. payment-service and notification-service both already
     * returned 401; auth-service was the odd one out.
     */
    @Test
    @DisplayName("a request with no credentials gets 401, not 403")
    void missingCredentialsIs401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a request with an unusable Authorization header gets 401, not 403")
    void malformedCredentialsIs401() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A path variable that cannot be parsed is a bad request. This used to fall
     * through to the catch-all handler and return 500 "Something went wrong",
     * which blames the server for a malformed URL the client sent.
     */
    @Test
    @DisplayName("a malformed ownerId on the internal endpoint is 400, not 500")
    void malformedPathVariableIs400() throws Exception {
        mockMvc.perform(get("/api/internal/users/not-a-uuid/notification-preferences")
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a syntactically valid but unknown ownerId is 404, not 400")
    void unknownOwnerIdIs404() throws Exception {
        mockMvc.perform(get("/api/internal/users/00000000-0000-0000-0000-000000000000/notification-preferences")
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the internal endpoint is read-only")
    void internalEndpointRejectsWrites() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/internal/users/00000000-0000-0000-0000-000000000000/notification-preferences")
                        .header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("emailEnabled", false))))
                .andExpect(status().isMethodNotAllowed());
    }
}
