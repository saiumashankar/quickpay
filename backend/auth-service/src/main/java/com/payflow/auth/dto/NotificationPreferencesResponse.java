package com.payflow.auth.dto;

import com.payflow.auth.entity.User;

import java.util.UUID;

/**
 * Returned by the internal endpoint consumed by notification-service.
 * The current address is included because the value carried on the payment
 * event is a snapshot taken at payment time and may be stale.
 */
public record NotificationPreferencesResponse(
        Long userId,
        UUID ownerId,
        String email,
        boolean emailEnabled
) {
    public static NotificationPreferencesResponse from(User user) {
        return new NotificationPreferencesResponse(
                user.getId(),
                user.getOwnerId(),
                user.getEmail(),
                user.isNotificationEnabled()
        );
    }
}
