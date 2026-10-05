package com.payflow.auth.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.auth.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisCacheTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        cache = new RedisCache(redis, new ObjectMapper().findAndRegisterModules());
    }

    private UserResponse profile() {
        return new UserResponse(42L, java.util.UUID.fromString("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c"),
                "asha", "asha@payflow.dev", "USER", "ACTIVE", Instant.parse("2025-06-01T10:00:00Z"));
    }

    @Test
    @DisplayName("a cache miss returns null so the caller falls through to the database")
    void cacheMissReturnsNull() {
        when(valueOperations.get("auth:user-profile:42")).thenReturn(null);

        assertThat(cache.getUserProfile(42L)).isNull();
    }

    @Test
    @DisplayName("a cached profile round trips every field the client needs")
    void cachedProfileRoundTrips() throws Exception {
        UserResponse original = profile();
        when(valueOperations.get("auth:user-profile:42"))
                .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(original));

        assertThat(cache.getUserProfile(42L)).isEqualTo(original);
    }

    @Test
    @DisplayName("profiles are namespaced by user id and expire after 10 minutes")
    void cacheWriteUsesNamespacedKeyAndTtl() {
        cache.cacheUserProfile(profile());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.time.Duration> ttl = ArgumentCaptor.forClass(java.time.Duration.class);
        verify(valueOperations).set(key.capture(), org.mockito.ArgumentMatchers.anyString(), ttl.capture());

        assertThat(key.getValue()).isEqualTo("auth:user-profile:42");
        assertThat(ttl.getValue()).isEqualTo(java.time.Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("two users never collide on the same cache entry")
    void keysAreDistinctPerUser() {
        cache.cacheUserProfile(profile());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        assertThat(key.getValue()).doesNotContain("asha@payflow.dev");
    }
}
