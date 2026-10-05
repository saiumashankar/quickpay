package com.payflow.payment.dto;

/**
 * Identity of the caller, derived exclusively from a verified JWT.
 * No client supplied header can influence these values.
 *
 * handle comes from the token rather than from the request body. That is what
 * makes a transfer unspoofable: the sender's own name is never something the
 * caller gets to choose, so a payment cannot be recorded as having come from
 * somebody else by putting their handle in the payload.
 */
public record GatewayUserContext(Long userId, String ownerId, String role, String email, String handle) {

    /**
     * Used by tokens issued before the handle claim existed. Such a token cannot
     * send money, because a transfer has to be labelled with a sender, but it
     * can still read its own history.
     */
    public static GatewayUserContext withoutHandle(Long userId, String ownerId, String role, String email) {
        return new GatewayUserContext(userId, ownerId, role, email, null);
    }
}
