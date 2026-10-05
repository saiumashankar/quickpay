package com.payflow.auth.controller;

import com.payflow.auth.dto.NotificationPreferenceUpdateRequest;
import com.payflow.auth.dto.NotificationPreferencesResponse;
import com.payflow.auth.dto.UserResponse;
import com.payflow.auth.cache.RedisCache;
import com.payflow.auth.entity.User;
import com.payflow.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RedisCache redisCache;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal User user) {
        UserResponse cached = redisCache.getUserProfile(user.getId());
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        UserResponse profile = userService.toResponse(user);
        redisCache.cacheUserProfile(profile);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/me/notification-preferences")
    public ResponseEntity<NotificationPreferencesResponse> getNotificationPreferences(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(NotificationPreferencesResponse.from(user));
    }

    @PutMapping("/me/notification-preferences")
    public ResponseEntity<NotificationPreferencesResponse> updateNotificationPreferences(
            @AuthenticationPrincipal User principal,
            @RequestBody NotificationPreferenceUpdateRequest request) {
        User user = userService.setNotificationEnabled(principal, request.emailEnabled());
        redisCache.evictUserProfile(user.getId());
        return ResponseEntity.ok(NotificationPreferencesResponse.from(user));
    }
}
