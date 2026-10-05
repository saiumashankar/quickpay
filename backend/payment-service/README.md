# QuickPay — payment-service

Payment processing for QuickPay. Owns the payment lifecycle, guarantees a client retry
never produces a second charge, and publishes payment events for downstream consumers.

Part of the QuickPay microservices system. The root project contains the shared
architecture, Docker Compose stack, Eureka server, and frontend.

## Stack

Java 17, Spring Boot 4.1, PostgreSQL, Redis, Kafka, Spring Security (OAuth2 resource
server), Eureka client, Docker.

## Responsibilities

- Accepts `POST /payments` with a bearer access token and an `Idempotency-Key` header
- Derives caller identity **only** from verified JWT claims
- Enforces idempotency with SHA-256 request fingerprinting backed by Redis
- Writes the payment and two outbox events in one database transaction
- Publishes unpublished outbox events to the `payment.events` Kafka topic

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/payments` | Bearer + `Idempotency-Key` | Create a payment |
| `GET` | `/payments` | Bearer | List the caller's payments |
| `GET` | `/payments/{id}` | Bearer | Fetch one payment, scoped to the owner |
| `GET` | `/actuator/health` | none | Liveness and readiness |

## Design decisions worth explaining in an interview

**Identity comes from the token, not the request.** The service originally trusted
`X-Authenticated-*` headers, which any caller could forge. `AuthenticatedUserProvider`
now reads `userId`, `ownerId`, `role` and email from the verified JWT. The
`AuthenticatedUserProviderTest` suite covers the missing and malformed claim cases.

**Access tokens are distinguished from refresh tokens.** `AccessTokenTypeValidator`
requires `type == ACCESS`. Without it, the 7-day refresh token would work as a
credential on every endpoint, making the 15-minute access token pointless.

**Idempotency is fingerprint-based, not key-based alone.** A SHA-256 hash of
`amount + currency + recipient + description` is stored next to the payment id. An
identical retry replays the original payment; the same key with different details is
rejected with 409 rather than silently charging again. `BigDecimal.stripTrailingZeros()`
means `500` and `500.00` are recognised as the same request.

**Redis coordination is released by a Lua script, gated on ownership.** A reservation
is taken with `SET NX` for 2 minutes, then rewritten on commit and deleted on rollback.
Both paths run a compare-and-set script so a slow request can never delete a lock a
newer request now owns.

**The outbox pattern makes the event and the payment atomic.** Kafka cannot join a
PostgreSQL transaction, so publishing inside one risks announcing a payment that rolled
back, and publishing after one risks losing the event entirely. Instead the intent is
written to `payment_outbox` in the same transaction, and a scheduled job publishes it.
The Kafka record key is the payment id, so both events for a payment land on one
partition and keep their order.

## How the outbox is published

The publisher polls unpublished rows every second and used to publish them one at a time,
waiting on each broker acknowledgement before starting the next, so a batch cost one round
trip per event. It also held a `PESSIMISTIC_WRITE` lock across those waits, which blocked
every payment writing its own outbox row.

Three changes, each driven by a measurement rather than a hunch:

- **Pipeline the batch.** All sends are issued first, then acknowledgements are awaited, so
  a batch costs one round trip instead of N. Each event is judged on its own result rather
  than abandoning the batch on the first failure, which removed the head-of-line blocking
  this service previously had.
- **Drop the row lock.** It existed to stop two publisher instances double-sending an event.
  The consumer is now idempotent, so the race it prevented is absorbed downstream. The topic
  becomes at-least-once, which is the guarantee Kafka actually offers.
- **Index the poll.** `where published_at is null order by created_at` ran as a sequential
  scan and a sort over every row ever written; `idx_outbox_unpublished` makes it read only
  the unpublished rows.

Measured on the Compose stack, 1,000 payments and 2,000 outbox events:

| | before | after |
|---|---|---|
| events pending when the burst finished | 1,800 | 366 |
| time to drain the backlog | 31.75s | 1.10s |
| effective drain rate | 56.7/s | 332.7/s |

The broker was mostly idle in both runs. The bottleneck was this publisher, not the
infrastructure the project was criticised for over-provisioning.

## Testing

```bash
mvn test
```

48 tests, no external services required:

- `PaymentServiceIdempotencyTest` — replay, conflicting reuse, concurrent requests, the
  `SET NX` race, fingerprint stability across amount scale, commit vs rollback
  coordination, owner scoping
- `OutboxPublisherTest` — publish acknowledgement, message keying, retry on broker failure,
  a failure not blocking the rest of the batch, and the batch being sent before the first
  acknowledgement is awaited
- `AccessTokenTypeValidatorTest` — access accepted, refresh rejected, absent and
  malformed type claims
- `AuthenticatedUserProviderTest` — claim extraction and rejection of bad identities
- `PaymentServiceApplicationTests` — context wiring and gateway decline configuration

## Known limitations

- The outbox publisher retries a failed send forever, with no attempt counter and no
  producer-side dead-letter. An event the broker permanently refuses sits at the front of
  every poll. The consumer side has a DLT; this side does not.
- The publisher still awaits broker acknowledgements inside its database transaction, so it
  holds a connection for the duration of the batch. The row lock is gone, but the
  transaction is not.
- Idempotency keys are namespaced by `ownerId` and held in Redis. A unique index in
  PostgreSQL would give the same guarantee atomically inside the existing transaction
  and remove a dependency.
- `spring.jpa.hibernate.ddl-auto` is set to `update`, which cannot add a `NOT NULL`
  column to a populated table. Flyway should own the schema.

## Local development

The service expects PostgreSQL, Redis and Kafka. The root `backend/docker-compose.yml`
starts all of them plus the sibling services. See the platform README.
