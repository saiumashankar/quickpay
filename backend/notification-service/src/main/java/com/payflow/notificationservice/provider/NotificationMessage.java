package com.payflow.notificationservice.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One message, independent of how it will be delivered.
 *
 * Rendering is separated from sending on purpose. The wording of a receipt
 * should not change because a new transport was added, and the same receipt
 * goes out over email, SMS and a merchant webhook at once. If each provider
 * built its own body, those three would drift apart and a user would be told
 * three different things about one payment.
 */
public record NotificationMessage(
        UUID paymentId,
        Party party,
        Kind kind,
        BigDecimal amount,
        String currency,
        String subject,
        String body,
        /**
         * Where the provider should deliver it. An email address for EMAIL, a
         * phone number for SMS, and for WEBHOOK a merchant endpoint registered
         * against the sender.
         */
        String destination
) {

    public enum Party {
        SENDER,
        RECIPIENT
    }

    public enum Kind {
        EMAIL,
        SMS,
        WEBHOOK
    }

    public static NotificationMessage email(UUID paymentId, Party party, BigDecimal amount, String currency,
                                           String subject, String body, String address) {
        return new NotificationMessage(paymentId, party, Kind.EMAIL, amount, currency, subject, body, address);
    }

    public static NotificationMessage sms(UUID paymentId, Party party, BigDecimal amount, String currency,
                                         String body, String phoneNumber) {
        return new NotificationMessage(paymentId, party, Kind.SMS, amount, currency, null, body, phoneNumber);
    }

    public static NotificationMessage webhook(UUID paymentId, Party party, BigDecimal amount, String currency,
                                              String subject, String body, String endpoint) {
        return new NotificationMessage(paymentId, party, Kind.WEBHOOK, amount, currency, subject, body, endpoint);
    }

    /**
     * Distinguishes this exact message from the other ones produced by the same
     * payment.
     *
     * It deliberately does not include the payment id: the caller already passes
     * that separately, so including it here would store it twice in the key.
     * Both party and kind are needed, because one payment produces two receipts
     * per channel and a marker per payment would let the first message sent
     * suppress all the others.
     */
    public String dedupKey() {
        return party + ":" + kind;
    }
}
