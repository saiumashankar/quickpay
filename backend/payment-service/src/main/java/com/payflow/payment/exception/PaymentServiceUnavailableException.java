package com.payflow.payment.exception;

/**
 * A dependency this service needs in order to answer correctly is unavailable.
 *
 * Kept separate from PaymentBadRequestException because the two look identical
 * to the caller who made the request and mean opposite things to the operator:
 * a bad request will never succeed if repeated, an unavailable dependency will.
 * Mapped to 503 so a client can retry rather than treat the transfer as
 * permanently rejected.
 */
public class PaymentServiceUnavailableException extends RuntimeException {
    public PaymentServiceUnavailableException(String message) {
        super(message);
    }
}
