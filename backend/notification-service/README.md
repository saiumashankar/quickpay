# QuickPay — notification-service

Turns payment events into email. Subscribes to the `payment.events` Kafka topic and
sends a notification when a payment reaches a terminal state.

Deliberately has **no dependency on payment-service**. It never calls it, and it works
even when payment-service is down. That is the payoff of the event-driven split.

Part of the QuickPay microservices system. The root project contains the shared
architecture, Docker Compose stack, Eureka server, and frontend.

## Stack

Java 17, Spring Boot 4.1, Spring Kafka, Spring Mail, Spring Security (OAuth2 resource
server), Spring Cloud OpenFeign + LoadBalancer, Resilience4j circuit breaker, Redis,
Eureka client, Docker.

Mailpit stands in for a real SMTP provider locally, so no email ever leaves the machine.

## Responsibilities

- Consume `payment.events` as part of the `notification-group` consumer group
- Deserialise the JSON payload into a `PaymentEvent`
- Ignore `PAYMENT_INITIATED`; the outcome is not known yet
- Resolve the customer's **current** email and notification preference from auth-service
- Skip the email if the customer has opted out
- Suppress a redelivery of an event already processed
- Send a success or failure email

## Why this service calls auth-service

This is the only synchronous service-to-service call in the platform, and it exists because
the event payload is a snapshot. A payment carries the email address as it was when the
payment was created, so a customer who changes their address or opts out afterwards would
still be notified from a queued event. Current state lives in auth-service, so this service
looks it up:

```
auth-service      (email, notificationPreferencesEnabled)  <- source of truth
notification-service  -> AuthServiceClient (Feign, resolved by Eureka)
                     -> Resilience4j circuit breaker
                     -> 60s PreferencesCache
```

`AuthServiceClient` calls the service name `auth-service` rather than a host and port. The
Eureka client resolves it, which is the first real consumer of the registry in this project.
The call is guarded by a shared `X-Internal-Token` on the auth side and fails closed when
that token is unset.

Two decisions here are worth arguing about:

- **Failing open.** If auth-service is unreachable, the resolver falls back to the email in
  the event and sends anyway. A payment receipt is better delivered than not delivered, but
  this is exactly the wrong default for legally binding consent. It is one flag in
  `NotificationPreferencesResolver`, and flipping it should be a deliberate decision, not an
  accident.
- **A 60-second cache.** The first version cached for 15 minutes and a live test showed a
  customer waiting that long for an email after opting back in. 60 seconds bounds the wait
  to something that does not read as broken.

## How a message is handled

```
Kafka record
  -> null check            (tombstone: acknowledge and skip, not a failure)
  -> ObjectMapper          (unknown fields ignored, so the schema can evolve)
  -> deserialisation error -> non-retryable -> payment.events.DLT
  -> ProcessedEventStore   (Redis SET NX on paymentId+eventType; skip if already sent)
  -> NotificationPreferencesResolver  (cache -> auth-service -> fail-open fallback)
  -> opted out?            -> suppress, no email
  -> JavaMailSender        (MailException propagates -> retry -> payment.events.DLT)
```

Failures are no longer swallowed. Both paths used to be logged and dropped, which meant a
broken SMTP server lost the customer's receipt with nothing but a log line. Now a malformed
payload goes straight to `payment.events.DLT` because a poison message will never parse on
redelivery, and a mail failure is retried before it is dead-lettered.

The cost of that is honest: retrying a mail failure holds the partition, so a total SMTP
outage slows the topic rather than silently discarding receipts. Bounded and visible loss
beats unbounded and silent loss.

## Testing

```bash
mvn test
```

59 tests, no broker or SMTP server required:

- `PaymentEventConsumerTest` — valid payload mapping, unknown-field tolerance, malformed
  JSON, empty and null payloads, partial payloads
- `NotificationServiceTest` — success and failure emails, initiated events send nothing,
  missing customer email skipped, unknown and null event types ignored, type trimming,
  suppression on opt-out and on a duplicate marker, SMTP failure propagation
- `NotificationPreferencesResolverTest` — cache hit, cache expiry, opt-out suppression,
  fail-open on an auth outage, circuit breaker opening
- `PreferencesCacheTest` — TTL, capacity eviction
- `ProcessedEventStoreTest` — marker set, duplicate suppressed, store failure falls back to
  the local set
- `AccessTokenTypeValidatorTest` — refresh tokens rejected on the test endpoint
- `NotificationServiceApplicationTests` — context wiring without a broker

## Known limitations

- Preference lookups fail open, so a stale opt-out can still produce an email for up to the
  cache lifetime plus any auth-service outage.
- The dedup marker is written after the SMTP call returns, so a crash in that window can
  still produce one duplicate. Closing it needs the marker and the send in a single
  transaction, which does not exist here.
- The marker TTL is seven days. Replaying an event from further back is treated as new.
- Redis holds the dedup markers, and there is a per-process fallback. A Redis outage
  degrades deduplication rather than stopping the consumer.
- The internal auth call authenticates with a shared secret, not per-client credentials.
- The consumer is a single-threaded listener, so one slow send serialises the partition.

## Local development

The service expects a Kafka broker. The root `backend/docker-compose.yml` starts Kafka,
creates the `payment.events` topic, and routes mail to Mailpit on
[http://localhost:8025](http://localhost:8025).
