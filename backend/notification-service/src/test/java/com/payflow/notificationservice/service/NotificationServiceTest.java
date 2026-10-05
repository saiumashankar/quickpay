package com.payflow.notificationservice.service;

import com.payflow.notificationservice.config.NotificationProperties;
import com.payflow.notificationservice.messaging.ProcessedEventStore;
import com.payflow.notificationservice.model.PaymentEvent;
import com.payflow.notificationservice.model.PaymentStatus;
import com.payflow.notificationservice.provider.EmailNotificationProvider;
import com.payflow.notificationservice.provider.NotificationProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationPreferencesResolver preferencesResolver;

    @Mock
    private ProcessedEventStore processedEvents;

    private NotificationService notificationService;

    /**
     * A real EmailNotificationProvider over a mocked JavaMailSender. The provider
     * is not mocked so that the rendering, the addressing and the failure
     * translation are all genuinely exercised; only the SMTP conversation is
     * faked.
     */
    private EmailNotificationProvider emailProvider;

    @BeforeEach
    void setUp() {
        NotificationProperties properties =
                new NotificationProperties("no-reply@payflow.dev", null, null);
        emailProvider = new EmailNotificationProvider(mailSender, properties);
        notificationService = new NotificationService(properties, preferencesResolver, processedEvents,
                new NotificationProviderRegistry(List.of(emailProvider)));
        // Null safe on purpose: a test that re-stubs this method calls the mock
        // with null while building the new stubbing, which would otherwise
        // blow up inside this answer before the test even runs.
        when(preferencesResolver.resolve(any()))
                .thenAnswer(invocation -> {
                    PaymentEvent event = invocation.getArgument(0);
                    return RecipientDecision.send(event == null ? "stub@payflow.dev" : event.userEmail(), "test");
                });
        when(preferencesResolver.resolveRecipient(any()))
                .thenAnswer(invocation -> {
                    PaymentEvent event = invocation.getArgument(0);
                    return RecipientDecision.send(
                            event == null ? "stub@payflow.dev" : event.recipientEmail(), "test");
                });
    }

    /**
     * A transfer between two different people. Both receipts are expected, so
     * the recipient address is present and the owner ids differ.
     */
    private PaymentEvent event(String eventType, String userEmail) {
        return new PaymentEvent(eventType, UUID.randomUUID(), 42L,
                "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c", userEmail, "asha",
                new BigDecimal("500.00"), "USD", "merchant",
                "1b0f6a5e-1f0a-4f0e-9b3a-2c3d4e5f6a7b", "merchant@payflow.dev", "Lunch",
                null,
                "SUCCESS".equals(eventType) ? com.payflow.notificationservice.model.PaymentStatus.SUCCESS
                        : com.payflow.notificationservice.model.PaymentStatus.FAILED,
                Instant.now());
    }

    /**
     * Every message sent for one event, in order. A successful transfer produces
     * two, so the assertions that care about one party's wording pick the one
     * addressed to that party rather than assuming there is only ever one.
     */
    private List<SimpleMailMessage> captureMessages() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    private SimpleMailMessage captureMessage() {
        return captureMessages().get(0);
    }

    private SimpleMailMessage messageTo(String address) {
        return captureMessages().stream()
                .filter(mail -> mail.getTo() != null && mail.getTo()[0].equals(address))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no message was sent to " + address));
    }

    @Test
    @DisplayName("a success event produces one email to the customer, not to the payee")
    void successEventSendsEmailToCustomer() {
        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        SimpleMailMessage mail = messageTo("asha@payflow.dev");
        assertThat(mail.getFrom()).isEqualTo("no-reply@payflow.dev");
        assertThat(mail.getSubject()).isEqualTo("Payment successful");
        assertThat(mail.getText()).contains("successful").contains("500").contains("@merchant");
    }

    @Test
    @DisplayName("the payer is told who they paid, and only that")
    void senderIsToldTheRecipientHandle() {
        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        assertThat(messageTo("asha@payflow.dev").getText()).contains("You sent money to @merchant");
    }

    @Test
    @DisplayName("the payee is told they received money and who sent it")
    void recipientIsNotifiedWithTheirOwnWording() {
        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        SimpleMailMessage toPayee = messageTo("merchant@payflow.dev");
        assertThat(toPayee.getSubject()).isEqualTo("You received money");
        assertThat(toPayee.getText())
                .contains("You received money from @asha")
                .contains("500");
    }

    @Test
    @DisplayName("both parties are told about a completed transfer")
    void bothPartiesAreNotified() {
        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        assertThat(captureMessages()).hasSize(2);
    }

    @Test
    @DisplayName("the payer's own note is not disclosed to the payee")
    void descriptionIsNotSharedWithThePayee() {
        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        assertThat(messageTo("merchant@payflow.dev").getText()).doesNotContain("Lunch");
    }

    @Test
    @DisplayName("the payee is not told about a transfer that never happened")
    void recipientIsNotNotifiedOfAFailure() {
        notificationService.handlePaymentEvent(event("PAYMENT_FAILED", "asha@payflow.dev"));

        assertThat(captureMessages()).hasSize(1);
        assertThat(captureMessage().getTo()).containsExactly("asha@payflow.dev");
    }

    @Test
    @DisplayName("the payee opting out suppresses only their own receipt")
    void recipientOptOutSuppressesOnlyTheirReceipt() {
        when(preferencesResolver.resolveRecipient(any()))
                .thenReturn(RecipientDecision.suppress("opted out"));

        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        assertThat(captureMessages()).hasSize(1);
        assertThat(captureMessage().getTo()).containsExactly("asha@payflow.dev");
    }

    @Test
    @DisplayName("an event with no recipient, from a producer that predates two sided transfers, still notifies the sender")
    void eventWithoutRecipientNotifiesSenderOnly() {
        PaymentEvent legacy = new PaymentEvent("PAYMENT_SUCCESS", UUID.randomUUID(), 42L,
                "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c", "asha@payflow.dev", "asha",
                new BigDecimal("500.00"), "USD", "merchant",
                null, null, null, null, PaymentStatus.SUCCESS, Instant.now());

        notificationService.handlePaymentEvent(legacy);

        assertThat(captureMessages()).hasSize(1);
        verify(preferencesResolver, never()).resolveRecipient(any());
    }

    @Test
    @DisplayName("a failure event produces a failure email")
    void failureEventSendsFailureEmail() {
        notificationService.handlePaymentEvent(event("PAYMENT_FAILED", "asha@payflow.dev"));

        assertThat(captureMessage().getSubject()).isEqualTo("Payment failed");
    }

    @Test
    @DisplayName("an initiated event sends nothing: the outcome is not known yet")
    void initiatedEventSendsNoEmail() {
        notificationService.handlePaymentEvent(event("PAYMENT_INITIATED", "asha@payflow.dev"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("when both parties have opted out, nothing is sent")
    void optedOutUserGetsNoEmail() {
        when(preferencesResolver.resolve(any()))
                .thenReturn(RecipientDecision.suppress("opted out"));
        when(preferencesResolver.resolveRecipient(any()))
                .thenReturn(RecipientDecision.suppress("opted out"));

        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("a redelivered event does not resend either receipt")
    void duplicateDeliveryIsSuppressed() {
        PaymentEvent event = event("PAYMENT_SUCCESS", "asha@payflow.dev");
        when(processedEvents.isProcessed(event.paymentId(), "SENDER:EMAIL")).thenReturn(false, true);
        when(processedEvents.isProcessed(event.paymentId(), "RECIPIENT:EMAIL")).thenReturn(false, true);

        notificationService.handlePaymentEvent(event);
        notificationService.handlePaymentEvent(event);

        // Two messages in total: both parties on the first delivery, neither on
        // the redelivery.
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }
    @Test
    @DisplayName("the address auth-service returns wins over the one on the event")
    void currentAddressFromAuthServiceIsUsed() {
        when(preferencesResolver.resolve(any()))
                .thenReturn(RecipientDecision.send("new-address@payflow.dev", "preferences from auth-service"));

        notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "stale@payflow.dev"));

        assertThat(captureMessage().getTo()).containsExactly("new-address@payflow.dev");
    }

    @Test
    @DisplayName("an unknown event type is ignored instead of crashing the consumer")
    void unknownEventTypeIsIgnored() {
        notificationService.handlePaymentEvent(event("PAYMENT_EXPLODED", "asha@payflow.dev"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("a null event type is ignored")
    void nullEventTypeIsIgnored() {
        notificationService.handlePaymentEvent(event(null, "asha@payflow.dev"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("padded event types are trimmed before parsing")
    void eventTypeIsTrimmed() {
        notificationService.handlePaymentEvent(event("  PAYMENT_SUCCESS  ", "asha@payflow.dev"));

        assertThat(captureMessage().getSubject()).isEqualTo("Payment successful");
    }

    @Test
    @DisplayName("a successful send records a per party marker so redelivery cannot resend either receipt")
    void successfulSendMarksEventProcessed() {
        PaymentEvent event = event("PAYMENT_SUCCESS", "asha@payflow.dev");

        notificationService.handlePaymentEvent(event);

        // Distinct markers per party, or the first receipt to be sent would
        // suppress the second and the payee would never be told.
        verify(processedEvents).markProcessed(event.paymentId(), "SENDER:EMAIL");
        verify(processedEvents).markProcessed(event.paymentId(), "RECIPIENT:EMAIL");
    }

    @Test
    @DisplayName("a failed send propagates so the container can retry then dead letter it")
    void mailFailurePropagatesForRetry() {
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
        PaymentEvent event = event("PAYMENT_SUCCESS", "asha@payflow.dev");

        // The SMTP exception is wrapped rather than rethrown raw, so the error
        // handler can tell a retryable delivery failure apart from a malformed
        // payload without knowing anything about Spring Mail.
        assertThatThrownBy(() -> notificationService.handlePaymentEvent(event))
                .isInstanceOf(NotificationSendException.class)
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("a send failure is reported as retryable, because a relay outage does clear")
    void mailFailureIsClassifiedRetryable() {
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> notificationService.handlePaymentEvent(event("PAYMENT_SUCCESS", "asha@payflow.dev")))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(thrown -> assertThat(((NotificationSendException) thrown).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("a failed send leaves the marker unset, so the retry is not suppressed as a duplicate")
    void failedSendDoesNotMarkEventProcessed() {
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
        PaymentEvent event = event("PAYMENT_SUCCESS", "asha@payflow.dev");

        assertThatThrownBy(() -> notificationService.handlePaymentEvent(event))
                .isInstanceOf(NotificationSendException.class);

        verify(processedEvents, never()).markProcessed(any(), anyString());
    }
}
