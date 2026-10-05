package com.payflow.auth.dto;

import com.payflow.auth.entity.User;

import java.util.UUID;

/**
 * The public identity of a user, addressed by handle instead of by email.
 *
 * payment-service needs this to turn "@sai123" into an ownerId before it can
 * move money, and auth-service owns the only copy of the handle to user mapping,
 * so the lookup happens here rather than being duplicated into a second store.
 */
public record UserHandleResponse(
        Long userId,
        UUID ownerId,
        String handle,
        String email
) {
    public static UserHandleResponse from(User user) {
        return new UserHandleResponse(
                user.getId(),
                user.getOwnerId(),
                user.getDisplayUsername(),
                user.getEmail());
    }
}
