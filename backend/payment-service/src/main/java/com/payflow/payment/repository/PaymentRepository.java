package com.payflow.payment.repository;

import com.payflow.payment.entity.Payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndOwnerId(UUID id, String ownerId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);
}
