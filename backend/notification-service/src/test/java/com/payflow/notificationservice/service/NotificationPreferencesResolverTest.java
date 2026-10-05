package com.payflow.notificationservice.service;

import com.payflow.notificationservice.client.AuthServiceClient;
import com.payflow.notificationservice.client.NotificationPreferences;
import com.payflow.notificationservice.model.PaymentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationPreferencesResolverTest {

    private static final UUID OWNER_ID = UUID.fromString("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c");
    private static final String TOKEN = "internal-secret";

    @Mock
    private AuthServiceClient authServiceClient;

    @Mock
    private CircuitBreakerFactory circuitBreakerFactory;

    @Mock
    @SuppressWarnings("unchecked")
    private org.springframework.cloud.client.circuitbreaker.CircuitBreaker circuitBreaker;

    private PreferencesCache cache;
    private NotificationPreferencesResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(circuitBreakerFactory.create("authService")).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(java.util.function.Supplier.class), any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> supplier = invocation.getArgument(0);
                    java.util.function.Function<Throwable, ?> fallback = invocation.getArgument(1);
                    try {
                        return supplier.get();
                    } catch (Throwable throwable) {
                        return fallback.apply(throwable);
                    }
                });
        cache = new PreferencesCache(java.time.Duration.ofMinutes(5));
        resolver = new NotificationPreferencesResolver(authServiceClient, cache, circuitBreakerFactory, TOKEN);
    }

    private PaymentEvent event(String ownerId, String userEmail) {
        return new PaymentEvent("PAYMENT_SUCCESS", UUID.randomUUID(), 42L, ownerId, userEmail,
                "asha", new BigDecimal("500.00"), "USD", "merchant",
                "1b0f6a5e-1f0a-4f0e-9b3a-2c3d4e5f6a7b", "merchant@payflow.dev", "Lunch", null,
                com.payflow.notificationservice.model.PaymentStatus.SUCCESS, Instant.now());
    }

    @Test
    @DisplayName("a user who opted out is suppressed even though the event carries an address")
    void optedOutUserIsSuppressed() {
        when(authServiceClient.getNotificationPreferences(eq(OWNER_ID), eq(TOKEN)))
                .thenReturn(new NotificationPreferences(42L, OWNER_ID, "asha@payflow.dev", false));

        RecipientDecision decision = resolver.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));

        assertThat(decision.suppressed()).isTrue();
        assertThat(decision.reason()).isEqualTo("opted out");
        assertThat(decision.email()).isNull();
    }

    @Test
    @DisplayName("the current address from auth-service is preferred over the snapshot on the event")
    void currentAddressWins() {
        when(authServiceClient.getNotificationPreferences(eq(OWNER_ID), eq(TOKEN)))
                .thenReturn(new NotificationPreferences(42L, OWNER_ID, "moved@payflow.dev", true));

        RecipientDecision decision = resolver.resolve(event(OWNER_ID.toString(), "stale@payflow.dev"));

        assertThat(decision.suppressed()).isFalse();
        assertThat(decision.email()).isEqualTo("moved@payflow.dev");
    }

    @Test
    @DisplayName("a second event for the same user is served from cache without another call")
    void secondEventHitsTheCache() {
        when(authServiceClient.getNotificationPreferences(eq(OWNER_ID), eq(TOKEN)))
                .thenReturn(new NotificationPreferences(42L, OWNER_ID, "asha@payflow.dev", true));

        resolver.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));
        resolver.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));

        verify(authServiceClient).getNotificationPreferences(eq(OWNER_ID), eq(TOKEN));
    }

    @Test
    @DisplayName("auth-service being down fails open: the receipt is still sent to the event address")
    void lookupFailureFailsOpen() {
        when(authServiceClient.getNotificationPreferences(any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        RecipientDecision decision = resolver.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));

        assertThat(decision.suppressed()).isFalse();
        assertThat(decision.email()).isEqualTo("asha@payflow.dev");
        assertThat(decision.reason()).contains("auth-service lookup failed");
    }

    @Test
    @DisplayName("a timeout also fails open rather than stalling the consumer")
    void timeoutFailsOpen() {
        // The Feign method does not declare checked exceptions, so a timeout
        // arrives wrapped. What matters here is only that a non-2xx or no
        // response at all reaches the fallback.
        when(authServiceClient.getNotificationPreferences(any(), any()))
                .thenThrow(new RuntimeException("read timed out after 1500ms"));

        RecipientDecision decision = resolver.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));

        assertThat(decision.suppressed()).isFalse();
        assertThat(decision.email()).isEqualTo("asha@payflow.dev");
    }

    @Test
    @DisplayName("no configured token means no remote call at all")
    void blankTokenSkipsTheLookup() {
        NotificationPreferencesResolver noToken =
                new NotificationPreferencesResolver(authServiceClient, cache, circuitBreakerFactory, "  ");

        RecipientDecision decision = noToken.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));

        assertThat(decision.email()).isEqualTo("asha@payflow.dev");
        assertThat(decision.reason()).contains("no internal api token");
        verify(authServiceClient, never()).getNotificationPreferences(any(), any());
    }

    @Test
    @DisplayName("an ownerId that is not a UUID falls back instead of calling out")
    void malformedOwnerIdFallsBack() {
        RecipientDecision decision = resolver.resolve(event("not-a-uuid", "asha@payflow.dev"));

        assertThat(decision.email()).isEqualTo("asha@payflow.dev");
        assertThat(decision.reason()).contains("no usable ownerId");
        verify(authServiceClient, never()).getNotificationPreferences(any(), any());
    }

    @Test
    @DisplayName("auth-service returning no address falls back to the event rather than sending nowhere")
    void blankAddressFromAuthServiceFallsBack() {
        when(authServiceClient.getNotificationPreferences(eq(OWNER_ID), eq(TOKEN)))
                .thenReturn(new NotificationPreferences(42L, OWNER_ID, "  ", true));

        RecipientDecision decision = resolver.resolve(event(OWNER_ID.toString(), "asha@payflow.dev"));

        assertThat(decision.email()).isEqualTo("asha@payflow.dev");
    }

    @Test
    @DisplayName("no address anywhere suppresses rather than guessing a recipient")
    void noAddressAnywhereSuppresses() {
        when(authServiceClient.getNotificationPreferences(any(), any()))
                .thenThrow(new IllegalStateException("auth-service down"));

        RecipientDecision decision = resolver.resolve(event(OWNER_ID.toString(), null));

        assertThat(decision.suppressed()).isTrue();
    }
}
