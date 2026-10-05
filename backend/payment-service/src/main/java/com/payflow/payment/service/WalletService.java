package com.payflow.payment.service;

import com.payflow.payment.client.HandleOwner;
import com.payflow.payment.dto.WalletResponse;
import com.payflow.payment.entity.Wallet;
import com.payflow.payment.exception.PaymentBadRequestException;
import com.payflow.payment.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns the balance. Every read or write of money in this service goes through
 * here, so there is exactly one place where a balance can change and one place
 * that decides whether it may.
 *
 * The wallet is created the first time it is needed rather than by a listener on
 * user registration. Creating it eagerly would mean this service depends on
 * registration having happened, in the right order, before the first transfer,
 * and a user who registered while this service was down would have no wallet at
 * all. Creating it lazily makes every path self-sufficient: a wallet exists
 * before it can be used, and a user who never transfers money never needs one.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    /**
     * What a brand new wallet is opened with.
     *
     * Zero is the honest default: a wallet with no money cannot send money, so
     * it cannot be drained by this choice. Anything else would be free money and
     * would have to be reconciled.
     */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("0.0000");

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public WalletResponse view(String ownerId) {
        return walletRepository.findByOwnerId(ownerId)
                .map(WalletResponse::from)
                .orElseGet(() -> WalletResponse.opening(ownerId, null, OPENING_BALANCE, null));
    }

    /**
     * Returns the wallet for this owner, creating an empty one if this is the
     * first time they have been seen.
     *
     * Must be called inside the transfer's transaction so that the row it
     * returns is the row the subsequent lock will protect.
     */
    @Transactional
    public Wallet getOrCreate(String ownerId, String handle, String currency) {
        return walletRepository.findByOwnerId(ownerId)
                .map(existing -> {
                    // The handle can change after registration. Refreshing it
                    // here keeps a transfer history readable without a second
                    // copy of the name that would have to be migrated.
                    if (handle != null && !handle.equals(existing.getHandle())) {
                        existing.setHandle(handle);
                    }
                    return existing;
                })
                .orElseGet(() -> createWallet(ownerId, handle, currency));
    }

    /**
     * Adds money to the caller's own wallet.
     *
     * There is no card processor behind this. It stands in for the funding step
     * a real product would put here, and it is deliberately the only way to
     * increase a balance so that no code path can credit money that was not
     * paid for. It is not idempotent: topping up twice tops up twice, because
     * a client retrying a top-up after a timeout has genuinely asked for two.
     */
    @Transactional
    public WalletResponse topUp(String ownerId, String handle, BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentBadRequestException("Top up amount must be greater than zero");
        }
        Wallet wallet = getOrCreate(ownerId, handle, currency);
        requireMatchingCurrency(wallet, currency);
        wallet.credit(amount);
        return WalletResponse.from(walletRepository.save(wallet));
    }

    /**
     * Locks both wallets of a transfer and returns them keyed by ownerId.
     *
     * Both rows are taken in a single statement so that the balance read and the
     * balance write are against the same locked version of the row. Reading the
     * balance unlocked and then locking to write it would allow two concurrent
     * transfers to both pass a check against the same balance and both spend it.
     */
    @Transactional
    public Map<String, Wallet> lockBoth(String senderOwnerId, String recipientOwnerId) {
        List<Wallet> locked = walletRepository.findAllByOwnerIdInForUpdate(
                java.util.List.of(senderOwnerId, recipientOwnerId));

        Map<String, Wallet> byOwner = locked.stream()
                .collect(Collectors.toMap(Wallet::getOwnerId, Function.identity()));

        // A wallet that was just created is already held by this transaction, so
        // it cannot be missing here. If it is, the invariant that a wallet
        // exists before it is used has been broken and the transfer must not
        // proceed on a balance nobody locked.
        if (!byOwner.containsKey(senderOwnerId) || !byOwner.containsKey(recipientOwnerId)) {
            throw new IllegalStateException("Expected a wallet for both parties of the transfer");
        }
        return byOwner;
    }

    private Wallet createWallet(String ownerId, String handle, String currency) {
        try {
            Wallet wallet = walletRepository.saveAndFlush(
                    new Wallet(ownerId, handle, OPENING_BALANCE, currency));
            log.info("Opened wallet for owner {} (@{})", ownerId, handle);
            return wallet;
        } catch (DataIntegrityViolationException alreadyExists) {
            // Two concurrent first transfers for the same new user both found no
            // wallet and both tried to create one. The primary key decided it,
            // so re-read and let the caller use the row that won.
            log.info("Wallet for owner {} was created concurrently, using the existing row", ownerId);
            return walletRepository.findByOwnerId(ownerId).orElseThrow(() -> alreadyExists);
        }
    }

    private void requireMatchingCurrency(Wallet wallet, String currency) {
        // A wallet's currency is fixed when it is opened, so there is nothing to
        // set here. Rejecting rather than converting is the honest answer: this
        // service holds no FX rates, and silently treating two currencies as one
        // would let a transfer move the wrong amount of money.
        if (wallet.getCurrency() != null && currency != null
                && !wallet.getCurrency().equalsIgnoreCase(currency)) {
            throw new PaymentBadRequestException(
                    "This wallet holds " + wallet.getCurrency() + ", cannot operate in " + currency);
        }
    }
}
