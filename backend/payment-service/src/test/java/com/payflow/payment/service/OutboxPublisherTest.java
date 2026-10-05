package com.payflow.payment.service;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.entity.OutboxEvent;
import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentEventType;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.util.concurrent.CompletableFuture;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxRepository, kafkaTemplate);
    }

    private OutboxEvent unpublishedEvent(PaymentEventType type) {
        Payment payment = new Payment(
                new GatewayUserContext(42L, "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c", "USER", "asha@payflow.dev", "asha"),
                new CreatePaymentRequest(new BigDecimal("500.00"), "USD", "merchant", "Lunch"),
                "merchant", "1b0f6a5e-1f0a-4f0e-9b3a-2c3d4e5f6a7b", "merchant@payflow.dev",
                PaymentStatus.PENDING);
        return new OutboxEvent(payment, type, "{\"eventType\":\"" + type.name() + "\"}");
    }

    @SuppressWarnings("unchecked")
    private void givenKafkaAck() {
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn((CompletableFuture<SendResult<String, String>>) (CompletableFuture<?>) future);
    }

    @Test
    @DisplayName("a confirmed send marks the event published and clears nothing else")
    void successfulPublishMarksEventPublished() {
        OutboxEvent event = unpublishedEvent(PaymentEventType.PAYMENT_SUCCESS);
        when(outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(anyInt())).thenReturn(List.of(event));
        givenKafkaAck();

        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt()).isNotNull();
        verify(kafkaTemplate).send("payment.events", event.getAggregateId(), event.getPayload());
    }

    @Test
    @DisplayName("the record key is the aggregate id so events for one payment keep their order")
    void messageKeyIsAggregateId() {
        OutboxEvent event = unpublishedEvent(PaymentEventType.PAYMENT_INITIATED);
        when(outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(anyInt())).thenReturn(List.of(event));
        givenKafkaAck();

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("payment.events"), eq(event.getAggregateId()), anyString());
        assertThat(UUID.fromString(event.getAggregateId())).isNotNull();
    }

    @Test
    @DisplayName("a broker failure leaves the event unpublished so the next poll retries it")
    void failedPublishLeavesEventUnpublished() {
        OutboxEvent event = unpublishedEvent(PaymentEventType.PAYMENT_SUCCESS);
        when(outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(anyInt())).thenReturn(List.of(event));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new CompletionException(new RuntimeException("broker down")));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn((CompletableFuture<SendResult<String, String>>) (CompletableFuture<?>) failed);

        publisher.publishPendingEvents();

        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("nothing is sent when there is nothing to publish")
    void emptyOutboxSendsNothing() {
        when(outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(anyInt())).thenReturn(List.of());

        publisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    /**
     * The publisher used to return on the first failure, so one undeliverable
     * event blocked everything queued behind it. It now judges each event on its
     * own result, which is what this pins down.
     */
    @Test
    @DisplayName("one undeliverable event no longer blocks the rest of the batch")
    void failureDoesNotBlockLaterEvents() {
        OutboxEvent broken = unpublishedEvent(PaymentEventType.PAYMENT_SUCCESS);
        OutboxEvent healthy = unpublishedEvent(PaymentEventType.PAYMENT_FAILED);
        when(outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(anyInt()))
                .thenReturn(List.of(broken, healthy));

        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new ExecutionException(new RuntimeException("broker down")));
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> ok = new CompletableFuture<>();
        ok.complete(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn((CompletableFuture<SendResult<String, String>>) (CompletableFuture<?>) failed)
                .thenReturn((CompletableFuture<SendResult<String, String>>) (CompletableFuture<?>) ok);

        publisher.publishPendingEvents();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        assertThat(broken.getPublishedAt()).isNull();
        assertThat(healthy.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("the whole batch is sent before the first acknowledgement is awaited")
    void sendsArePipelined() {
        OutboxEvent first = unpublishedEvent(PaymentEventType.PAYMENT_INITIATED);
        OutboxEvent second = unpublishedEvent(PaymentEventType.PAYMENT_SUCCESS);
        OutboxEvent third = unpublishedEvent(PaymentEventType.PAYMENT_FAILED);
        when(outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(anyInt()))
                .thenReturn(List.of(first, second, third));
        givenKafkaAck();

        publisher.publishPendingEvents();

        // A sequential send().get() per event would mark the first event
        // published before the second was ever sent. All three are sent first,
        // so the batch costs one round trip rather than three.
        verify(kafkaTemplate, times(3)).send(anyString(), anyString(), anyString());
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
        assertThat(third.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("an event is only marked published after Kafka acknowledges it")
    void publishedAtIsNullBeforeAcknowledgement() {
        OutboxEvent event = unpublishedEvent(PaymentEventType.PAYMENT_SUCCESS);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
