package com.payflow.auth.dto;

/**
 * Body for PUT /api/users/me/notification-preferences.
 */
public record NotificationPreferenceUpdateRequest(Boolean emailEnabled) {
}
