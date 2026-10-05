package com.payflow.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * What auth-service knows about the owner of a handle.
 *
 * Deliberately not the User entity: payment-service must not be able to read or
 * write identity records, only to resolve a handle to an ownerId it can credit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HandleOwner(
        Long userId,
        UUID ownerId,
        String handle,
        String email
) {
}
