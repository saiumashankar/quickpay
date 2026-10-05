package com.payflow.notificationservice.service;

import com.payflow.notificationservice.client.NotificationPreferences;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived local cache of notification preferences.
 *
 * The point is to avoid a network call on the common path. A Kafka consumer
 * processes events one at a time, and without this every single notification
 * would open an HTTP connection to auth-service for data that changes maybe
 * once a quarter. A local read also means a suspended auth-service does not
 * stall the consumer thread.
 *
 * The TTL is deliberately short because one of the two fields is a consent
 * decision. An earlier 15 minute default was wrong and live testing caught it:
 * a user who opted back in stayed suppressed for the rest of the window. At
 * 60 seconds the worst case is that a user who opts out receives at most one
 * more receipt per minute, which is a far better trade than ignoring a fresh
 * opt-in. The address field changes far less often and would tolerate a
 * longer TTL, but splitting the two would double the calls for little gain.
 *
 * The cache is bounded to avoid unbounded growth on a long-lived consumer.
 */
@Component
public class PreferencesCache {

    private static final int MAX_ENTRIES = 10_000;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public PreferencesCache(@Value("${payflow.preferences.cache-ttl:60s}") Duration ttl) {
        this.ttl = ttl;
    }

    public NotificationPreferences get(UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        Entry entry = entries.get(ownerId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            entries.remove(ownerId, entry);
            return null;
        }
        return entry.preferences();
    }

    public void put(UUID ownerId, NotificationPreferences preferences) {
        if (ownerId == null || preferences == null) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(ownerId, new Entry(preferences, Instant.now().plus(ttl)));
    }

    public void evict(UUID ownerId) {
        if (ownerId != null) {
            entries.remove(ownerId);
        }
    }

    public int size() {
        return entries.size();
    }

    private record Entry(NotificationPreferences preferences, Instant expiresAt) {
    }
}
