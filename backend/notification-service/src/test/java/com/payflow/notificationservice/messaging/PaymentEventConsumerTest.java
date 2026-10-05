package com.payflow.notificationservice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.notificationservice.model.PaymentEvent;
import com.payflow.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(new ObjectMapper().findAndRegisterModules(), notificationService);
    }

    private static final String VALID_PAYLOAD = """
            {
              "eventType": "PAYMENT_SUCCESS",
              "paymentId": "147ebf18-5a45-4d19-bf88-81368ce38628",
              "userId": 42,
              "ownerId": "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c",
              "userEmail": "asha@payflow.dev",
              "amount": 500.00,
              "currency": "USD",
              "recipient": "merchant@payflow.dev",
              "status": "SUCCESS",
              "occurredAt": "2026-01-15T10:30:00Z"
            }
            """;

    @Test
    @DisplayName("a well formed payload is deserialised and handed to the notification service")
        void validPayloadIsHandled() throws JsonProcessingException {
        consumer.onPaymentEvent(VALID_PAYLOAD);

        ArgumentCaptor<PaymentEvent> captor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(notificationService).handlePaymentEvent(captor.capture());

        PaymentEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("PAYMENT_SUCCESS");
        assertThat(event.paymentId()).hasToString("147ebf18-5a45-4d19-bf88-81368ce38628");
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.ownerId()).isEqualTo("6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c");
        assertThat(event.userEmail()).isEqualTo("asha@payflow.dev");
        assertThat(event.amount()).isEqualByComparingTo("500.00");
        assertThat(event.status().name()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("unknown fields are ignored so the producer can evolve the schema")
    void unknownFieldsAreTolerated() {
        String payload = VALID_PAYLOAD.replace("\"currency\": \"USD\",",
                "\"currency\": \"USD\", \"merchantTier\": \"gold\", \"newField\": 42,");

        assertThatCode(() -> consumer.onPaymentEvent(payload)).doesNotThrowAnyException();
        verify(notificationService).handlePaymentEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("malformed JSON is rethrown so the error handler can dead letter it")
    void malformedPayloadIsDeadLettered() {
        assertThatThrownBy(() -> consumer.onPaymentEvent("{ this is not json "))
                .isInstanceOf(JsonProcessingException.class);

        verify(notificationService, never()).handlePaymentEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an empty payload is rethrown rather than silently acknowledged")
    void emptyPayloadIsDeadLettered() {
        assertThatThrownBy(() -> consumer.onPaymentEvent(""))
                .isInstanceOf(JsonProcessingException.class);

        verify(notificationService, never()).handlePaymentEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a null payload is acknowledged without action: a tombstone cannot be replayed")
    void nullPayloadIsSkipped() {
        assertThatCode(() -> consumer.onPaymentEvent(null)).doesNotThrowAnyException();

        verify(notificationService, never()).handlePaymentEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a missing field is tolerated: the event still arrives for logging")
        void partialPayloadIsTolerated() throws JsonProcessingException {
        String payload = """
                {"eventType":"PAYMENT_FAILED","paymentId":"147ebf18-5a45-4d19-bf88-81368ce38628"}
                """;

        consumer.onPaymentEvent(payload);

        ArgumentCaptor<PaymentEvent> captor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(notificationService).handlePaymentEvent(captor.capture());
        assertThat(captor.getValue().userEmail()).isNull();
    }
}
