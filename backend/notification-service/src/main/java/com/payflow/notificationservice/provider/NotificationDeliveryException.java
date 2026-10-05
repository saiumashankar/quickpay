package com.payflow.notificationservice.provider;

/**
 * A provider could not deliver a message.
 *
 * Checked deliberately: it is the one exception an implementation is allowed to
 * throw, so a provider that is wired up wrong fails at compile time rather than
 * silently succeeding and swallowing real delivery failures as unchecked
 * exceptions nobody anticipated.
 *
 * The {@code retryable} flag exists because not every failure is worth
 * retrying. An SMTP server that is down will probably come back, so retry. A
 * webhook that answered 404 will answer 404 forever, so retrying it only holds
 * up the partition and delays every later event behind it.
 */
public class NotificationDeliveryException extends Exception {

    private final boolean retryable;

    public NotificationDeliveryException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static NotificationDeliveryException retryable(String message, Throwable cause) {
        return new NotificationDeliveryException(message, cause, true);
    }

    public static NotificationDeliveryException permanent(String message) {
        return new NotificationDeliveryException(message, null, false);
    }

    public static NotificationDeliveryException permanent(String message, Throwable cause) {
        return new NotificationDeliveryException(message, cause, false);
    }

    /**
     * Whether the container should try this message again.
     *
     * A permanent failure is still thrown rather than swallowed: it propagates
     * so the record is acknowledged through the dead letter path and there is a
     * durable record of it, rather than the message being dropped with only a
     * log line. The error handler in KafkaConsumerConfig is what turns it into
     * a parked record instead of a retry loop.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
