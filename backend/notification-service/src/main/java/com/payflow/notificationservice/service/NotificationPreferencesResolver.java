package com.payflow.notificationservice.service;

import com.payflow.notificationservice.client.AuthServiceClient;
import com.payflow.notificationservice.client.NotificationPreferences;
import com.payflow.notificationservice.model.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Decides where a payment notification should be sent.
 *
 * auth-service owns the user record, so it owns the opt-out flag and the
 * current address. The email on the payment event is a snapshot taken when the
 * payment was made: it cannot reflect an opt-out added since, and it cannot
 * reflect an address change. This is the one piece of data in the system that
 * genuinely has to be fetched, which is why there is a service call here at all.
 *
 * Fail-open is a deliberate choice. If auth-service is unreachable the lookup
 * falls back to the address on the event and still sends. The reasoning: the
 * only harm is that someone who opted out receives one extra receipt while
 * auth-service is down, whereas suppressing on failure means a user with a real
 * payment does not get its receipt at all. Consent is only ever violated
 * because it could not be read, never because it was read and ignored. If this
 * system handled legally binding notices instead of receipts, the trade would
 * flip, and the reason is recorded in DECISIONS.md.
 */
@Service
public class NotificationPreferencesResolver {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferencesResolver.class);

    private final AuthServiceClient authServiceClient;
    private final PreferencesCache cache;
    private final CircuitBreakerFactory circuitBreakerFactory;
    private final String internalToken;

    public NotificationPreferencesResolver(
            AuthServiceClient authServiceClient,
            PreferencesCache cache,
            CircuitBreakerFactory circuitBreakerFactory,
            @Value("${payflow.internal.api-token:}") String internalToken) {
        this.authServiceClient = authServiceClient;
        this.cache = cache;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.internalToken = internalToken;
    }

    public RecipientDecision resolve(PaymentEvent event) {
        return resolveFor(parseOwnerId(event.ownerId()), event.userEmail(), event.paymentId(), "sender");
    }

    /**
     * The same decision for the person who received the money.
     *
     * Treated exactly like the sender rather than as a special case: they have
     * their own opt-out flag and their own current address in auth-service, and
     * assuming they would want the receipt because the payer does would be
     * exactly the kind of consent violation the sender path avoids.
     */
    public RecipientDecision resolveRecipient(PaymentEvent event) {
        return resolveFor(parseOwnerId(event.recipientOwnerId()), event.recipientEmail(),
                event.paymentId(), "recipient");
    }

    private RecipientDecision resolveFor(UUID ownerId, String eventEmail, UUID paymentId, String party) {
        if (ownerId == null) {
            return fallbackToEvent(eventEmail, "event carries no usable ownerId for the " + party);
        }
        if (internalToken.isBlank()) {
            return fallbackToEvent(eventEmail, "no internal api token configured");
        }

        NotificationPreferences cached = cache.get(ownerId);
        if (cached == null) {
            NotificationPreferences fetched = fetch(ownerId);
            if (fetched == null) {
                return fallbackToEvent(eventEmail,
                        "auth-service lookup failed for the " + party + ", using address from event");
            }
            cache.put(ownerId, fetched);
            cached = fetched;
        }

        if (!cached.emailEnabled()) {
            log.info("{} {} has opted out of email notifications, suppressing payment {}",
                    party, ownerId, paymentId);
            return RecipientDecision.suppress("opted out");
        }
        if (cached.email() == null || cached.email().isBlank()) {
            return fallbackToEvent(eventEmail, "auth-service returned no address");
        }
        if (!cached.email().equals(eventEmail)) {
            log.info("Using current address {} for payment {} instead of the address on the event {}",
                    cached.email(), paymentId, eventEmail);
        }
        return RecipientDecision.send(cached.email(), "preferences from auth-service");
    }

    /**
     * Spring Cloud has no @CircuitBreaker annotation, so the breaker is taken
     * from the factory by name. The call is already bounded by the Feign read
     * timeout, so a slow auth-service trips the breaker on failure rather than
     * on a separate time limiter.
     */
    public NotificationPreferences fetch(UUID ownerId) {
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("authService");
        return circuitBreaker.run(
                () -> authServiceClient.getNotificationPreferences(ownerId, internalToken),
                cause -> {
                    log.warn("Could not read notification preferences for {}: {}. Falling back to the event address.",
                            ownerId, cause.toString());
                    return null;
                });
    }

    private RecipientDecision fallbackToEvent(String eventEmail, String reason) {
        if (eventEmail == null || eventEmail.isBlank()) {
            return RecipientDecision.suppress(reason + " and the event carries no address");
        }
        return RecipientDecision.send(eventEmail, reason);
    }

    private UUID parseOwnerId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(ownerId.trim());
        } catch (IllegalArgumentException exception) {
            log.warn("Event ownerId '{}' is not a UUID, cannot look up preferences", ownerId);
            return null;
        }
    }
}
