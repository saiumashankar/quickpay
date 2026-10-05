package com.payflow.payment.dto;

import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentEventType;
import com.payflow.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire contract for the payment.events topic. Consumed by notification-service.
 *
 * It carries both sides of the transfer, which is what lets notification-service
 * tell each person about a payment without having to work out who they are:
 *
 *  - the sender's ownerId and email, so the payer can be addressed.
 *  - the recipient's ownerId and email, so the payee can be addressed. In a
 *    wallet-to-wallet transfer the recipient is a real account with a real
 *    mailbox, unlike the payee of a card payment where there is no user on the
 *    other side to notify at all.
 *
 * Both emails are snapshots taken at transfer time. notification-service treats
 * them as fallbacks and prefers the current address from auth-service, because a
 * snapshot cannot reflect an opt-out or an address change that happened after
 * the transfer.
 */
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
        PaymentStatus status,
        Instant occurredAt
) {
    public static PaymentEvent from(Payment payment, PaymentEventType eventType, PaymentStatus eventStatus) {
        return new PaymentEvent(eventType.name(), payment.getId(), payment.getUserId(), payment.getOwnerId(),
                payment.getUserEmail(), payment.getSenderHandle(), payment.getAmount(), payment.getCurrency(),
                payment.getRecipientHandle(), payment.getRecipientOwnerId(), payment.getRecipientEmail(),
                payment.getDescription(), eventStatus, Instant.now());
    }
}
