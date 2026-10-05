package com.payflow.payment.repository;

import com.payflow.payment.entity.OutboxEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * No pessimistic lock, deliberately.
     *
     * The publisher used to take a write lock on the batch and hold it for the
     * whole time it waited on the broker. That stopped a second publisher
     * instance from double sending an event, but it also blocked every payment
     * writing a new outbox row for the duration of the round trip, and a larger
     * batch made it worse: measured p99 on POST /payments went from 94 ms to
     * 687 ms after the batch size was raised to 500.
     *
     * The trade is now safe because the consumer is idempotent. Two publishers
     * racing on the same row can put the event on the topic twice, and
     * notification-service drops the second copy via its processed marker. The
     * payment path stays uncontended, and the topic is at-least-once instead of
     * exactly-once, which is the guarantee Kafka actually offers.
     */
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt asc")
    List<OutboxEvent> findTopBatchesByPublishedAtIsNullOrderByCreatedAtAsc(int batchSize);
}
