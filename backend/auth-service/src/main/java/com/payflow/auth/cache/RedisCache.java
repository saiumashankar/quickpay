package com.payflow.auth.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.auth.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisCache {
    private static final String USER_PROFILE_KEY = "auth:user-profile:";
    private static final Duration USER_PROFILE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public UserResponse getUserProfile(Long userId) {
        String value = redis.opsForValue().get(USER_PROFILE_KEY + userId);
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, UserResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read cached user profile", ex);
        }
    }

    public void cacheUserProfile(UserResponse profile) {
        try {
            String value = objectMapper.writeValueAsString(profile);
            redis.opsForValue().set(USER_PROFILE_KEY + profile.id(), value, USER_PROFILE_TTL);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not cache user profile", ex);
        }
    }

    public void evictUserProfile(Long userId) {
        redis.delete(USER_PROFILE_KEY + userId);
    }
}
