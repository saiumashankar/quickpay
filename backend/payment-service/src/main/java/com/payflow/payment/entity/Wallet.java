package com.payflow.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A user's balance. One row per ownerId, so the primary key is the same
 * identifier that appears on a payment and on a JWT.
 *
 * There is no separate account id: the wallet is not something a user chooses
 * or transfers, it is the money that belongs to an owner. Using ownerId as the
 * key means the balance can never be attached to the wrong person by a
 * mismatched foreign key, and there is no second identifier to keep in sync.
 *
 * handle is a cached copy of the name auth-service issued. It is stored so a
 * transfer history still renders correctly if a user later changes their handle,
 * and it is never used to look a wallet up: resolution goes through auth-service
 * and arrives as an ownerId.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(name = "owner_id", nullable = false, length = 36, updatable = false)
    private String ownerId;

    @Column(nullable = false, length = 30)
    private String handle;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Belt to the braces of the row lock in WalletRepository.
     *
     * The pessimistic lock is what actually serialises two transfers touching
     * the same wallet, because it makes the second one wait rather than read a
     * stale balance. This version is the backstop for anything that reaches a
     * wallet without that lock, such as a top-up on a request path that only
     * did a plain read: the update then fails instead of silently overwriting a
     * balance somebody else has already changed.
     *
     * It is not free. Every balance write now carries a round trip to check the
     * version, and a lost update is reported as an error a client has to retry
     * rather than as a transfer that went through.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Wallet() {
    }

    public Wallet(String ownerId, String handle, BigDecimal openingBalance, String currency) {
        this.ownerId = ownerId;
        this.handle = handle;
        this.balance = openingBalance;
        this.currency = currency;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.updatedAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Wallet " + ownerId + " has insufficient funds");
        }
        this.balance = this.balance.subtract(amount);
        this.updatedAt = Instant.now();
    }

    public boolean hasAtLeast(BigDecimal amount) {
        return this.balance.compareTo(amount) >= 0;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
