package com.payflow.payment.service;

import com.payflow.payment.repository.OutboxRepository;
import com.payflow.payment.entity.OutboxEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "payment.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String TOPIC = "payment.events";
    private static final int BATCH_SIZE = 200;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends are issued for the whole batch before any of them is awaited.
     *
     * The earlier version called send().get() per event, so a batch of 100 cost
     * 100 sequential broker round trips. Measured on the compose stack that ran
     * at about 57 events per second, which meant a 363 payments per second burst
     * left roughly 1800 events queued and took 32 seconds to drain. The broker
     * was nowhere near its limit; the round trips were the constraint. Issuing
     * the sends first lets them overlap, so a batch costs about one round trip
     * in total.
     *
     * A failure no longer returns. It used to abandon the rest of the batch, so
     * one undeliverable event blocked every event behind it until it was fixed.
     * Now each event is judged on its own result: the ones that were accepted
     * are marked published and the rest stay unpublished for the next poll.
     *
     * The batch is read without a row lock so that waiting on the broker never
     * blocks a payment from writing its own outbox row. That makes the topic
     * at-least-once, which the consumer's processed marker absorbs.
     */
    @Scheduled(fixedDelayString = "${payment.outbox.publish-interval:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(BATCH_SIZE);
        if (events.isEmpty()) {
            return;
        }

        List<CompletableFuture<?>> inFlight = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            inFlight.add(kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload()));
        }

        for (int index = 0; index < events.size(); index++) {
            OutboxEvent event = events.get(index);
            try {
                inFlight.get(index).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markPublished();
            } catch (Exception exception) {
                log.error("Could not publish outbox event {} to Kafka, leaving it unpublished for retry: {}",
                        event.getId(), exception.toString());
            }
        }
    }
}
