package com.payflow.notificationservice.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.payflow.notificationservice.service.NotificationSendException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Turns "the message failed" from a silent loss into a parked record.
 *
 * Retries use exponential backoff over roughly 30 seconds before a message is
 * dead lettered. The ceiling matters: without it a broker outage would have
 * the consumer spinning on one record until the container gave up, and the
 * partition would still be stuck.
 *
 * Records land on <original-topic>-<original-suffix> (payment.events.DLT by
 * default) with the failure headers attached. The original key and payload are
 * preserved, which is what makes replay possible: a repaired message can be
 * re-published under its original key and keep its partition ordering.
 *
 * This exposes a single CommonErrorHandler bean, which Spring Boot applies to
 * the default listener container factory on its own. Overriding the factory
 * bean instead would have meant taking the auto-configured one as a dependency
 * of itself.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final String DLT_SUFFIX = ".DLT";
    private static final long INITIAL_INTERVAL_MS = 1_000;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 10_000;
    private static final long MAX_ELAPSED_MS = 30_000;

    @Bean
    DefaultErrorHandler paymentEventErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        backOff.setMaxElapsedTime(MAX_ELAPSED_MS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Acknowledge only once the record has been recovered, so an
        // interrupted retry is never mistaken for a handled message.
        errorHandler.setAckAfterHandle(false);
        // A payload that will not parse will not parse on the third attempt
        // either. Marking it non-retryable sends it straight to the dead letter
        // topic instead of holding the partition for 30 seconds of pointless
        // retries, which is what delays every later event on the same partition.
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        // A provider that answered "no" rather than "not now": a webhook that
        // returned 4xx, or a notification addressed to nobody. The same answer
        // is coming on every attempt, so retrying only holds up the partition.
        // It still goes to the dead letter topic rather than being dropped, so
        // there is a durable record of a receipt that was never delivered.
        errorHandler.addNotRetryableExceptions(NotificationSendException.class);
        return errorHandler;
    }
}
