package com.payflow.notificationservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer between two wallets.
 *
 * Both parties are on the event. ownerId and userEmail are the sender, and
 * recipientOwnerId and recipientEmail are the person the money went to. In a
 * wallet-to-wallet transfer that second person is a real account with a real
 * mailbox, which is why they can be told "you received 500 from @asha" without
 * anybody having to be looked up after the fact.
 *
 * The recipient fields are null on events published by an older payment-service.
 * The notification for the sender is unaffected, and the recipient is simply not
 * told, which is the safe direction to degrade in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(
        String eventType,
        UUID paymentId,
        Long userId,
        String ownerId,
        String userEmail,
        String senderHandle,
        BigDecimal amount,
        String currency,
        String recipient,
        String recipientOwnerId,
        String recipientEmail,
        String description,
        String merchantWebhookUrl,
        PaymentStatus status,
        Instant occurredAt
) {

    public boolean hasRecipient() {
        return recipientOwnerId != null && !recipientOwnerId.isBlank();
    }    public boolean isTransferBetweenOthers() {
        return hasRecipient() && recipientOwnerId.equals(ownerId);
    }

    public boolean hasMerchantWebhook() {
        return merchantWebhookUrl != null && !merchantWebhookUrl.isBlank();
    }
}
