package com.payflow.notificationservice.service;

import com.payflow.notificationservice.provider.NotificationMessage;

/**
 * Wraps a provider failure so it can leave the consumer as an unchecked
 * exception and be classified by the container error handler.
 *
 * Unchecked because a provider is called deep inside a loop over channels, and
 * forcing every one of those call sites to handle a checked exception would add
 * handling that could only ever rethrow. The contract that a delivery failure
 * propagates is documented on NotificationProvider instead.
 */
public class NotificationSendException extends RuntimeException {

    private final transient NotificationMessage message;
    private final boolean retryable;

    public NotificationSendException(NotificationMessage message, boolean retryable, Throwable cause) {
        super("Failed to send the " + message.kind() + " " + message.party()
                + " notification for payment " + message.paymentId(), cause);
        this.message = message;
        this.retryable = retryable;
    }

    public NotificationMessage message() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
