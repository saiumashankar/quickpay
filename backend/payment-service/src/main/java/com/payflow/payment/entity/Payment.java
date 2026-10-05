package com.payflow.payment.entity;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.GatewayUserContext;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, length = 320)
    private String userEmail;

    /**
     * The sender's handle, taken from the JWT at the time of the transfer.
     * Stored rather than joined because a handle can change, and a payment from
     * last year should still read the way it was made.
     */
    @Column(nullable = false, length = 30)
    private String senderHandle;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * The recipient's handle: the name a person would recognise, which is all a
     * payment history needs to be readable.
     */
    @Column(nullable = false, length = 30)
    private String recipient;

    /**
     * Whose wallet was credited. This is what makes the transfer
     * wallet-to-wallet: the handle above is how the recipient is shown, and this
     * is the identity the money actually moved to.
     */
    @Column(name = "recipient_owner_id", nullable = false, length = 36)
    private String recipientOwnerId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(GatewayUserContext principal, CreatePaymentRequest request, String recipientHandle,
                   String recipientOwnerId, String recipientEmail, PaymentStatus status) {
        this.id = UUID.randomUUID();
        this.userId = principal.userId();
        this.ownerId = principal.ownerId();
        this.userEmail = principal.email();
        this.senderHandle = principal.handle();
        this.amount = request.amount();
        this.currency = request.currency();
        this.recipient = recipientHandle;
        this.recipientOwnerId = recipientOwnerId;
        this.recipientEmail = recipientEmail;
        this.description = request.description();
        this.status = status;
        this.createdAt = Instant.now();
    }

    public String getSenderHandle() {
        return senderHandle;
    }

    /**
     * The handle the recipient is shown by. Named distinctly from the raw column
     * so that a caller cannot mistake it for the identity that was credited.
     */
    public String getRecipientHandle() {
        return recipient;
    }

    public String getRecipientOwnerId() {
        return recipientOwnerId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
