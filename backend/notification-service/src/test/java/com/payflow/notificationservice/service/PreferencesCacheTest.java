package com.payflow.notificationservice.service;

import com.payflow.notificationservice.client.NotificationPreferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreferencesCacheTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final NotificationPreferences PREFS =
            new NotificationPreferences(42L, OWNER, "asha@payflow.dev", true);

    @Test
    @DisplayName("a stored preference is returned")
    void storesAndReturns() {
        PreferencesCache cache = new PreferencesCache(Duration.ofMinutes(15));

        cache.put(OWNER, PREFS);

        assertThat(cache.get(OWNER)).isEqualTo(PREFS);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an expired entry is not returned, so an opt-out change can take effect")
    void expiredEntryIsNotReturned() {
        PreferencesCache cache = new PreferencesCache(Duration.ofMillis(1));

        cache.put(OWNER, PREFS);
        sleep(10);

        assertThat(cache.get(OWNER)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("eviction drops the entry immediately")
    void evictRemovesEntry() {
        PreferencesCache cache = new PreferencesCache(java.time.Duration.ofMinutes(5));
        cache.put(OWNER, PREFS);

        cache.evict(OWNER);

        assertThat(cache.get(OWNER)).isNull();
    }

    @Test
    @DisplayName("a null owner id is ignored rather than stored under a null key")
    void nullOwnerIdIsIgnored() {
        PreferencesCache cache = new PreferencesCache(java.time.Duration.ofMinutes(5));

        cache.put(null, PREFS);

        assertThat(cache.get(null)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("a null preference is not stored")
    void nullPreferencesAreIgnored() {
        PreferencesCache cache = new PreferencesCache(java.time.Duration.ofMinutes(5));

        cache.put(OWNER, null);

        assertThat(cache.get(OWNER)).isNull();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
