package com.payflow.notificationservice.service;

import com.payflow.notificationservice.config.NotificationProperties;
import com.payflow.notificationservice.messaging.ProcessedEventStore;
import com.payflow.notificationservice.model.PaymentEvent;
import com.payflow.notificationservice.model.PaymentEventType;
import com.payflow.notificationservice.provider.NotificationDeliveryException;
import com.payflow.notificationservice.provider.NotificationMessage;
import com.payflow.notificationservice.provider.NotificationProvider;
import com.payflow.notificationservice.provider.NotificationProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a completed transfer into the messages the two people involved expect,
 * and hands each message to whichever provider handles that channel.
 *
 * Both sides are notified, and they are told different things. The payer wants
 * "you sent 500 to @sai123"; the payee wants "you received 500 from @asha".
 * Sending the payer's message to both would be wrong in a way that matters,
 * because it discloses the sender's own account activity to the person being
 * paid.
 *
 * On failure only the sender is notified. Nobody else's balance changed, so
 * telling the recipient about a transfer that never happened would be inventing
 * a payment.
 *
 * This class renders the wording and decides who gets told. It does not know how
 * anything is delivered: that is the registry's job, which is why adding SMS or
 * a merchant webhook did not require touching this file.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationProperties properties;
    private final NotificationPreferencesResolver preferencesResolver;
    private final ProcessedEventStore processedEvents;
    private final NotificationProviderRegistry providers;

    public NotificationService(NotificationProperties properties,
                               NotificationPreferencesResolver preferencesResolver,
                               ProcessedEventStore processedEvents,
                               NotificationProviderRegistry providers) {
        this.properties = properties;
        this.preferencesResolver = preferencesResolver;
        this.processedEvents = processedEvents;
        this.providers = providers;
    }

    public void handlePaymentEvent(PaymentEvent event) {
        log.info("Received payment event: eventType={}, paymentId={}, sender={}@{}, recipientOwnerId={}, amount={} {}, status={}, occurredAt={}",
                event.eventType(), event.paymentId(), event.senderHandle(), event.ownerId(),
                event.recipientOwnerId(), event.amount(), event.currency(), event.status(), event.occurredAt());

        PaymentEventType eventType = parseEventType(event);
        if (eventType == null) {
            log.warn("Unknown or missing eventType '{}' for payment {}, skipping", event.eventType(), event.paymentId());
            return;
        }
        if (eventType == PaymentEventType.PAYMENT_INITIATED) {
            // The outcome is not known yet. Announcing it would mean telling
            // somebody money arrived and then telling them it did not.
            log.info("Payment {} initiated, no terminal notification required", event.paymentId());
            return;
        }

        dispatch(event, eventType, preferencesResolver.resolve(event), Party.SENDER);

        if (eventType == PaymentEventType.PAYMENT_SUCCESS && event.hasRecipient() && !event.isTransferBetweenOthers()) {
            dispatch(event, eventType, preferencesResolver.resolveRecipient(event), Party.RECIPIENT);
        }
    }

    /**
     * Sends one party's receipt over every channel configured for it.
     *
     * Email is always attempted, because a user in this system is defined by
     * having an email address. SMS and webhooks only go out when the event
     * carries somewhere to send them; neither is guessed at, because inventing
     * a phone number or a merchant endpoint would be sending a payment receipt
     * to a stranger.
     */
    private void dispatch(PaymentEvent event, PaymentEventType eventType, RecipientDecision decision, Party party) {
        if (decision.suppressed()) {
            log.info("Not sending the {} {} notification for payment {}: {}",
                    eventType, party, event.paymentId(), decision.reason());
            return;
        }

        String subject = subjectFor(eventType, party);
        String body = bodyFor(event, eventType, party);

        deliver(NotificationMessage.email(event.paymentId(), party.toMessageParty(), event.amount(),
                event.currency(), subject, body, decision.email()));

        String phone = phoneFor(party, event);
        if (phone != null) {
            deliver(NotificationMessage.sms(event.paymentId(), party.toMessageParty(), event.amount(),
                    event.currency(), smsBody(event, eventType, party), phone));
        }

        String webhook = webhookFor(event);
        if (webhook != null) {
            deliver(NotificationMessage.webhook(event.paymentId(), party.toMessageParty(), event.amount(),
                    event.currency(), subject, body, webhook));
        }
    }

    /**
     * Hands one message to its provider, unless it has already gone out.
     *
     * A failure propagates. That is what lets the container retry the whole
     * record and, if it keeps failing, park it in the dead letter topic. The
     * dedup marker is written only after a success, so a message that failed is
     * not suppressed on the retry.
     */
    private void deliver(NotificationMessage message) {
        if (message.destination() == null || message.destination().isBlank()) {
            log.warn("No destination for a {} {} message on payment {}, skipping",
                    message.kind(), message.party(), message.paymentId());
            return;
        }

        NotificationProvider provider = providers.forKind(message.kind()).orElse(null);
        if (provider == null) {
            log.warn("No provider handles {}, dropping the message for payment {}",
                    message.kind(), message.paymentId());
            return;
        }
        if (!provider.isConfigured()) {
            log.info("Provider {} for {} is not configured on this deployment, skipping payment {}",
                    provider.name(), message.kind(), message.paymentId());
            return;
        }

        if (processedEvents.isProcessed(message.paymentId(), message.dedupKey())) {
            log.info("Already sent the {} {} notification for payment {}, skipping duplicate",
                    message.kind(), message.party(), message.paymentId());
            return;
        }

        try {
            provider.send(message);
        } catch (NotificationDeliveryException exception) {
            log.error("Provider {} failed for the {} {} notification on payment {} ({}): {}",
                    provider.name(), message.kind(), message.party(), message.paymentId(),
                    exception.isRetryable() ? "retryable" : "permanent", exception.getMessage());
            // Rethrown whether retryable or not. A permanent failure still needs
            // a durable record, and the error handler turns it into a dead
            // lettered message instead of a retry loop that could never succeed.
            throw new NotificationSendException(message, exception.isRetryable(), exception);
        }

        processedEvents.markProcessed(message.paymentId(), message.dedupKey());
        log.info("Sent the {} {} notification via {} for payment {}",
                message.kind(), message.party(), provider.name(), message.paymentId());
    }

    /**
     * No user in this system has a phone number, so SMS is only ever sent when
     * the event carries one. Rather than adding a phone field to the identity
     * model for a channel nobody has asked for, the plumbing is here and stays
     * dormant until a number exists to send to.
     */
    private String phoneFor(Party party, PaymentEvent event) {
        return switch (party) {
            case SENDER -> null;
            case RECIPIENT -> null;
        };
    }

    /**
     * The merchant endpoint a completed payment should be reported to, if any.
     *
     * Read from the event rather than configured here: which merchant to notify
     * is a property of the payment, not of this service. Returning null when
     * there is none is the normal case, not an error.
     */
    private String webhookFor(PaymentEvent event) {
        return event.merchantWebhookUrl();
    }

    private PaymentEventType parseEventType(PaymentEvent event) {
        if (event.eventType() == null || event.eventType().isBlank()) {
            return null;
        }
        try {
            return PaymentEventType.valueOf(event.eventType().trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String subjectFor(PaymentEventType eventType, Party party) {
        if (eventType == PaymentEventType.PAYMENT_FAILED) {
            return "Payment failed";
        }
        return party == Party.RECIPIENT ? "You received money" : "Payment successful";
    }

    /**
     * The SMS version of the receipt. Much shorter than the email, because a
     * message has a length limit and no room for the payment id and the note:
     * what somebody needs to know on a phone is the amount, the direction and
     * who it was with.
     */
    private String smsBody(PaymentEvent event, PaymentEventType eventType, Party party) {
        String amount = event.amount() == null ? "n/a" : event.amount().toPlainString();
        String currency = event.currency() == null ? "" : " " + event.currency();
        if (eventType == PaymentEventType.PAYMENT_FAILED) {
            return "Payflow: your payment could not be completed. You were not charged.";
        }
        return party == Party.RECIPIENT
                ? "Payflow: you received " + amount + currency + " from "
                        + handleOr(event.senderHandle(), event.ownerId()) + "."
                : "Payflow: you sent " + amount + currency + " to "
                        + handleOr(event.recipient(), event.recipientOwnerId()) + ".";
    }

    private String bodyFor(PaymentEvent event, PaymentEventType eventType, Party party) {
        StringBuilder body = new StringBuilder();
        if (eventType == PaymentEventType.PAYMENT_FAILED) {
            body.append("Your payment could not be completed. You were not charged.");
        } else if (party == Party.RECIPIENT) {
            body.append("You received money from ")
                    .append(handleOr(event.senderHandle(), event.ownerId()))
                    .append(".");
        } else {
            body.append("Your payment was successful. You sent money to ")
                    .append(handleOr(event.recipient(), event.recipientOwnerId()))
                    .append(".");
        }

        body.append(System.lineSeparator()).append(System.lineSeparator())
                .append("Payment id: ").append(event.paymentId()).append(System.lineSeparator())
                .append("Amount: ").append(event.amount() == null ? "n/a" : event.amount())
                .append(event.currency() == null || event.currency().isBlank() ? "" : " " + event.currency())
                .append(System.lineSeparator());
        if (event.status() != null) {
            body.append("Status: ").append(event.status()).append(System.lineSeparator());
        }
        if (eventType == PaymentEventType.PAYMENT_SUCCESS && party == Party.SENDER
                && event.description() != null && !event.description().isBlank()) {
            body.append("Note: ").append(event.description()).append(System.lineSeparator());
        }
        return body.toString();
    }

    /**
     * Prefers the handle because it is what a person recognises, and falls back
     * to the owner id so that an event from a producer that did not set one
     * still produces a readable message rather than "You received money from null".
     */
    private String handleOr(String handle, String ownerId) {
        if (handle != null && !handle.isBlank()) {
            return "@" + handle;
        }
        return ownerId == null || ownerId.isBlank() ? "someone" : ownerId;
    }

    private enum Party {
        SENDER,
        RECIPIENT;

        NotificationMessage.Party toMessageParty() {
            return this == SENDER ? NotificationMessage.Party.SENDER : NotificationMessage.Party.RECIPIENT;
        }
    }
}
