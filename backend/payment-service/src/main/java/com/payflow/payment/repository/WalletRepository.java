package com.payflow.payment.repository;

import com.payflow.payment.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    Optional<Wallet> findByOwnerId(String ownerId);

    /**
     * Takes a row lock on every wallet in the transfer, ordered by ownerId.
     *
     * A transfer touches two wallets and must not interleave with another
     * transfer touching either of them, so the balance is read under the lock
     * rather than in application code before it.
     *
     * The ORDER BY is load bearing, not cosmetic. Two concurrent transfers
     * between the same pair of users would otherwise be able to lock A then B
     * and B then A, and deadlock. Ordering every lock request by the same key
     * means at most one can be first, so one of them waits instead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.ownerId in :ownerIds order by w.ownerId")
    List<Wallet> findAllByOwnerIdInForUpdate(@Param("ownerIds") Collection<String> ownerIds);
}
