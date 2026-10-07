package com.payflow.payment.dto;

import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        Long userId,
        String senderHandle,
        BigDecimal amount,
        String currency,
        String recipientHandle,
        PaymentDirection direction,
        String description,
        PaymentStatus status,
        Instant createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return from(payment, payment.getOwnerId());
    }

    public static PaymentResponse from(Payment payment, String viewerOwnerId) {
        boolean received = payment.getRecipientOwnerId().equals(viewerOwnerId);
        return new PaymentResponse(payment.getId(), payment.getUserId(), payment.getSenderHandle(),
                payment.getAmount(), payment.getCurrency(), payment.getRecipientHandle(),
                received ? PaymentDirection.RECEIVED : PaymentDirection.SENT,
                payment.getDescription(), payment.getStatus(), payment.getCreatedAt());
    }
}
