package com.payflow.notificationservice.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis is mocked rather than started so the fallback behaviour can be tested
 * without a broker. The real Redis path is covered by the end to end script,
 * which runs against the compose stack.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessedEventStoreTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectProvider<StringRedisTemplate> provider;

    private UUID paymentId;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(valueOperations);
        paymentId = UUID.randomUUID();
    }

    @Test
    @DisplayName("an unmarked event is not considered processed")
    void unmarkedEventIsNotProcessed() {
        when(redis.hasKey(anyString())).thenReturn(false);
        ProcessedEventStore store = new ProcessedEventStore(provider);

        assertThat(store.isProcessed(paymentId, "PAYMENT_SUCCESS")).isFalse();
    }

    @Test
    @DisplayName("a marked event is recognised so a redelivery does not resend the receipt")
    void markedEventIsProcessed() {
        when(redis.hasKey(anyString())).thenReturn(true);
        ProcessedEventStore store = new ProcessedEventStore(provider);

        store.markProcessed(paymentId, "PAYMENT_SUCCESS");

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
        assertThat(store.isProcessed(paymentId, "PAYMENT_SUCCESS")).isTrue();
    }

    @Test
    @DisplayName("a different event type for the same payment is tracked separately")
    void eventTypesAreIndependent() {
        when(redis.hasKey(anyString())).thenReturn(false);
        ProcessedEventStore store = new ProcessedEventStore(provider);

        store.markProcessed(paymentId, "PAYMENT_SUCCESS");

        // The key is scoped by event type, so a later FAILED event is not
        // mistaken for the already sent SUCCESS receipt.
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.contains("PAYMENT_SUCCESS"),
                anyString(),
                any(Duration.class));
    }

    @Test
    @DisplayName("a Redis outage falls back to a local marker rather than blocking the consumer")
    void redisOutageFallsBackLocally() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        ProcessedEventStore store = new ProcessedEventStore(provider);

        store.markProcessed(paymentId, "PAYMENT_SUCCESS");

        assertThat(store.isProcessed(paymentId, "PAYMENT_SUCCESS")).isTrue();
    }

    @Test
    @DisplayName("with no Redis available the store still works in process")
    void worksWithoutRedis() {
        when(provider.getIfAvailable()).thenReturn(null);
        ProcessedEventStore store = new ProcessedEventStore(provider);

        store.markProcessed(paymentId, "PAYMENT_SUCCESS");

        assertThat(store.isProcessed(paymentId, "PAYMENT_SUCCESS")).isTrue();
        verify(redis, never()).hasKey(anyString());
    }
}
