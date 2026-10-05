package com.payflow.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Mirror of auth-service NotificationPreferencesResponse. Declared separately
 * so notification-service can be deployed without sharing a module, which is
 * the usual reason two services drift apart.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationPreferences(
        Long userId,
        UUID ownerId,
        String email,
        boolean emailEnabled
) {
}
