package com.payflow.notificationservice.provider;

/**
 * One way of getting a message out of this service.
 *
 * Implemented once per transport. Adding SMS or a webhook meant adding an
 * implementation of this interface and nothing else: no change to
 * NotificationService, to the consumer, or to the event contract.
 *
 * Every implementation must be safe to call more than once for the same
 * message. The consumer retries a whole record after any failure, so a provider
 * that partially succeeded and then threw will be asked to send the same
 * message again, and only the caller can tell that from a first attempt.
 *
 * Implementations throw {@link NotificationDeliveryException} on failure. They
 * must not swallow it: that is what lets the container retry and eventually
 * dead letter the record instead of losing the receipt silently.
 */
public interface NotificationProvider {

    /**
     * Which kind of message this provider handles. Used to select the provider
     * per message rather than calling all of them.
     */
    NotificationMessage.Kind kind();

    /**
     * A short name for logs and metrics.
     */
    String name();

    /**
     * @return true when the provider is configured well enough to be tried.
     * False means "not set up", which is not a failure: it is logged and
     * skipped. This is what lets a deployment run with email only and still
     * start cleanly.
     */
    boolean isConfigured();

    /**
     * @throws NotificationDeliveryException when the message could not be
     * delivered. Retrying the same call must either deliver it or fail again;
     * it must not report success for a message that was never sent.
     */
    void send(NotificationMessage message) throws NotificationDeliveryException;
}
