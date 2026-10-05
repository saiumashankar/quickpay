package com.payflow.auth.entity;

import com.payflow.auth.dto.NotificationPreferencesResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferencesTest {

    private User user(Boolean preferencesEnabled) {
        return User.builder()
                .username("asha")
                .email("asha@payflow.dev")
                .password("encoded")
                .role(Role.USER)
                .ownerId(UUID.fromString("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c"))
                .notificationPreferencesEnabled(preferencesEnabled)
                .build();
    }

    @Test
    @DisplayName("a user who never set a preference still receives notifications")
    void nullMeansEnabled() {
        assertThat(user(null).isNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("an explicit opt-out is honoured")
    void falseMeansDisabled() {
        assertThat(user(false).isNotificationEnabled()).isFalse();
    }

    @Test
    @DisplayName("an explicit opt-in is honoured")
    void trueMeansEnabled() {
        assertThat(user(true).isNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("the response carries the current address, not the one on the event")
    void responseCarriesCurrentAddress() {
        NotificationPreferencesResponse response = NotificationPreferencesResponse.from(user(false));

        assertThat(response.email()).isEqualTo("asha@payflow.dev");
        assertThat(response.emailEnabled()).isFalse();
        assertThat(response.ownerId())
                .isEqualTo(UUID.fromString("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c"));
    }
}
