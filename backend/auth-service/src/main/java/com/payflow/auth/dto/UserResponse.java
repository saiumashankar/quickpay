package com.payflow.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        Long id,
        UUID ownerId,
        String username,
        String email,
        String role,
        String status,
        Instant createdAt
) {
}
