package com.payflow.notificationservice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.notificationservice.model.PaymentEvent;
import com.payflow.notificationservice.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every failure path here propagates to the container error handler, which
 * retries and then parks the record in the dead letter topic.
 *
 * Returning instead of throwing was the earlier behaviour and it was wrong for
 * two different reasons. A poison message was dropped with nothing but a log
 * line, so the event was gone with no record that it had been lost. And a
 * failed email committed the offset, so an SMTP outage discarded every
 * notification in flight. Neither is recoverable after the fact; a dead letter
 * topic is.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public PaymentEventConsumer(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "${kafka.topic.payment-events:payment.events}",
            groupId = "${spring.kafka.consumer.group-id:notification-group}")
    public void onPaymentEvent(String payload) throws JsonProcessingException {
        if (payload == null) {
            // Only reachable for a tombstone on a compacted topic. There is no
            // record to retry, so it is logged and acknowledged deliberately
            // rather than dead lettered: parking nulls would fill the DLT with
            // entries that can never be processed.
            log.warn("Received a null payment event payload, acknowledging without action");
            return;
        }
        PaymentEvent event;
        try {
            event = objectMapper.readValue(payload, PaymentEvent.class);
        } catch (JsonProcessingException exception) {
            log.error("Could not deserialize payment event, sending to dead letter topic. payload={}",
                    payload, exception);
            throw exception;
        }
        log.debug("Consumed raw message from topic payment.events: {}", payload);
        notificationService.handlePaymentEvent(event);
    }
}
