package com.payflow.payment.exception;

public class PaymentForbiddenException extends RuntimeException {
    public PaymentForbiddenException(String message) {
        super(message);
    }
}
