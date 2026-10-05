package com.payflow.notificationservice.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which terminal payment notifications have already been sent, so
 * that Kafka's at-least-once redelivery cannot send the same receipt twice.
 *
 * Why a marker and not a database constraint, which is the usual answer for
 * payment idempotency: the side effect here is an SMTP call, and there is no
 * transaction that a database write could join. Any marker is therefore
 * separate from the effect, so this cannot be made atomic. See DECISIONS.md
 * for why Redis rather than a second database.
 *
 * The marker is written after a successful send, never before. Marking first
 * would mean a send that fails is suppressed on retry and the receipt is lost
 * permanently. Marking after means a crash in the narrow window between the
 * send and the write can produce one duplicate receipt, which is the milder
 * failure: the user sees two emails instead of none.
 *
 * Concurrent duplicates are not possible here even though the check and the
 * write are not atomic, because payment events are keyed by payment id and a
 * partition is only ever handled by one consumer at a time. Two events for the
 * same payment always land on the same partition and are processed in order.
 */
@Component
public class ProcessedEventStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventStore.class);
    private static final String KEY_PREFIX = "notification:processed:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final Map<String, Long> localFallback = new ConcurrentHashMap<>();

    public ProcessedEventStore(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider.getIfAvailable();
        if (this.redis == null) {
            log.warn("No Redis connection available; notification deduplication is process-local only "
                    + "and will not survive a restart");
        }
    }

    public boolean isProcessed(UUID paymentId, String eventType) {
        String key = key(paymentId, eventType);
        if (redis != null) {
            try {
                return Boolean.TRUE.equals(redis.hasKey(key));
            } catch (RuntimeException exception) {
                log.warn("Could not read the processed marker for {}: {}", key, exception.toString());
                return isProcessedLocally(key);
            }
        }
        return isProcessedLocally(key);
    }

    public void markProcessed(UUID paymentId, String eventType) {
        String key = key(paymentId, eventType);
        if (redis != null) {
            try {
                redis.opsForValue().set(key, "1", TTL);
                return;
            } catch (RuntimeException exception) {
                log.warn("Could not write the processed marker for {}: {}", key, exception.toString());
            }
        }
        localFallback.put(key, System.currentTimeMillis());
    }

    private boolean isProcessedLocally(String key) {
        Long writtenAt = localFallback.get(key);
        if (writtenAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - writtenAt > TTL.toMillis()) {
            localFallback.remove(key);
            return false;
        }
        return true;
    }

    private String key(UUID paymentId, String eventType) {
        return KEY_PREFIX + paymentId + ":" + eventType;
    }
}
