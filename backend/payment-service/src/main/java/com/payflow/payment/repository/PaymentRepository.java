package com.payflow.payment.repository;

import com.payflow.payment.entity.Payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndOwnerId(UUID id, String ownerId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
            SELECT p FROM Payment p
            WHERE p.ownerId = :ownerId
               OR (p.recipientOwnerId = :ownerId
                   AND p.status = com.payflow.payment.entity.PaymentStatus.SUCCESS)
            ORDER BY p.createdAt DESC
            """)
    List<Payment> findActivityByOwnerId(@Param("ownerId") String ownerId);
}
