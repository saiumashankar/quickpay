package com.payflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false"})
class AuthIntegrationTests {
    private static final String JWT_SECRET = "integration-test-secret-must-be-at-least-32-bytes";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("jwt.secret", () -> JWT_SECRET);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void registrationLoginAndJwtProtectedProfileWork() throws Exception {
        Map<String, String> registration = Map.of(
                "username", "integrationuser",
                "email", "integration@example.com",
                "password", "strong-password-123");
        ResponseEntity<String> registered = http.postForEntity("/api/auth/register", registration, String.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> login = http.postForEntity("/api/auth/login",
                Map.of("email", "integration@example.com", "password", "strong-password-123"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(login.getBody());
        String accessToken = body.get("accessToken").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> profile = http.exchange("/api/users/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(profile.getBody()).get("email").asText())
                .isEqualTo("integration@example.com");
    }

    @Test
    void rejectsDuplicateRegistration() {
        Map<String, String> registration = Map.of(
                "username", "duplicateuser",
                "email", "duplicate@example.com",
                "password", "strong-password-123");
        http.postForEntity("/api/auth/register", registration, String.class);
        ResponseEntity<String> duplicate = http.postForEntity("/api/auth/register", registration, String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsMethodNotAllowedForGetRegistration() {
        ResponseEntity<String> response = http.getForEntity("/api/auth/register", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
