package com.payflow.payment.dto;

import com.payflow.payment.entity.Wallet;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletResponse(
        String ownerId,
        String handle,
        BigDecimal balance,
        String currency,
        Instant createdAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getOwnerId(), wallet.getHandle(), wallet.getBalance(),
                wallet.getCurrency(), wallet.getCreatedAt());
    }

    /**
     * A wallet the caller has not opened yet is reported as empty rather than as
     * a 404. Not being able to send money because you have never received any
     * is the same user visible state as having a zero balance, and returning a
     * 404 here would make the frontend treat an ordinary account as missing.
     */
    public static WalletResponse opening(String ownerId, String handle, BigDecimal balance, String currency) {
        return new WalletResponse(ownerId, handle, balance, currency, null);
    }
}
