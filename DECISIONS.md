# Design decisions

Every significant choice in QuickPay, what it bought, and what it cost. Written so the
tradeoffs can be defended in an interview rather than just recalled.

## The problem being modelled

A client sends a payment request. The response is lost — a timeout, a dropped
connection, an impatient user double-clicking. The client cannot tell whether the money
moved, so it retries. Without protection, that retry is a second charge.

The second requirement follows from the first: once a payment is recorded, the user must
be told. But that notification must never be able to fail, delay, or block the payment.
A payment platform that loses money because an SMTP server was down has its priorities
inverted.

## Payments and notifications are decoupled on purpose

The naive design calls the notification service inline, inside the request. Two things
break: the user waits on an email to be sent, and a mail outage becomes a payment
outage. Neither is acceptable, so payment and notification are separated by a message
log.

**Rejected alternative:** an in-process `ApplicationEvent` with
`@TransactionalEventListener(AFTER_COMMIT)`. This is the right answer for most systems
and it is genuinely simpler. It was rejected here because the point of the project is to
demonstrate the durable, outbox-backed version of this pattern. In production at this
scale it would be the better choice, and saying so is a better interview answer than
defending Kafka unconditionally.

**Where this does not hold:** the decoupling is between `payment-service` and
`notification-service`. `notification-service` does make a synchronous call to
`auth-service` for the customer's current email and preference, and that call is
deliberately *not* on the payment path. A payment never waits on it; if auth is down the
notification falls back and the payment is unaffected. Recording that boundary matters,
because "the services are decoupled" is only true of the path that was actually measured.

## Transactional outbox

Kafka cannot participate in a PostgreSQL transaction. That forces one of three designs:

| Approach | Failure mode |
|---|---|
| Publish to Kafka inside the DB transaction | Transaction rolls back, but the event was already sent. Consumers act on a payment that never happened. |
| Commit, then publish | Process crashes in between. The payment is recorded and the notification is silently lost forever. |
| **Write the intent to the database, publish from there** | The event is either published or still waiting. No loss, no phantom events. |

The third option was chosen. A `payment_outbox` table is written inside the same
transaction as the payment, and a scheduled job publishes unpublished rows every second.

**Cost accepted:** an extra table, a polling job, and eventual consistency. A payment can
be committed for up to a second before its event reaches Kafka.

**Ordering:** the Kafka record key is the payment id, so both events for one payment hash
to the same partition and are consumed in the order they were written.
`PAYMENT_INITIATED` can never arrive after `PAYMENT_SUCCESS`.

**The remaining gap:** the publisher still retries a failed send indefinitely, with no
attempt counter and no producer-side dead-letter. An event the broker permanently refuses
occupies the front of the queue on every poll. An `attempts` counter with a ceiling that
routes to a dead-letter topic and leaves the row for operator replay is the fix. The
consumer side has this; the producer side does not yet.

## Idempotency keyed on a fingerprint, not just a key

Storing only the idempotency key is not enough. Reusing a key with *different* details is
a client bug, and silently treating it as a replay would charge the wrong amount.

The stored value is `SHA-256(amount + currency + recipient + description) + ":" + paymentId`.

| Incoming | Stored fingerprint | Outcome |
|---|---|---|
| Same | Same | Return the original payment |
| Different | Same key | 409, the key was reused for a different request |
| Same | Different | 409, still processing |

`BigDecimal.stripTrailingZeros()` in the canonical string means `500` and `500.00`
produce the same fingerprint, so a client-side formatting change is not mistaken for a
different payment.

**Why Redis and not PostgreSQL:** the check happens before any database work, on every
write request, and needs `SET NX` with a TTL. Redis answers that in under a millisecond.

**Honest counter-argument:** a unique index on `(owner_id, idempotency_key)` in
PostgreSQL would give the same guarantee *atomically inside the transaction that already
exists*, remove an entire datastore, and improve consistency. Redis was kept because
demonstrating cross-store coordination is part of the project, not because it is the
better engineering choice at this scale.

**Reservation lifecycle:** `SET NX` takes a 2-minute reservation. A
`TransactionSynchronization` then rewrites the value on commit (`PSETEX` with the 24-hour
TTL) and deletes it on rollback. Both run compare-and-set Lua scripts, so a slow request
can never delete a lock that a newer request now owns. Losing this ownership check would
be a subtle, hard-to-reproduce bug.

## JWT with two token lifetimes

| Token | Lifetime | Used for |
|---|---|---|
| Access | 15 minutes | Every API request |
| Refresh | 7 days | Minting a new access token |

A leaked access token is bounded at 15 minutes. The user is not forced to log in every
quarter hour.

The `type` claim is what makes the split enforceable. Both downstream services run
`AccessTokenTypeValidator`, which requires `type == ACCESS`. Without it, a 7-day refresh
token would work as a bearer credential on every endpoint, making the access token's
short lifetime meaningless.

**Cost accepted:** the secret is symmetric and shared, so a compromised consumer could
forge tokens for any user. RS256 with per-service keys fixes this and is contained to
two `JwtDecoder` beans.

**Identity comes from claims, never from request input.** The service previously trusted
`X-Authenticated-*` headers, which any caller could forge to become any user.
`AuthenticatedUserProvider` now reads `userId`, `ownerId`, `role` and email from the
verified token. This is the single most important security property in the project.

## `ownerId` as the ownership key

Each user gets a random UUID at registration, independent of the database id. Payment
records are scoped by `ownerId` rather than `userId` because it does not leak sequential
primary keys, survives a data migration, and gives one stable public identifier that
travels in the JWT.

## `userEmail` carried in the event

A payment's `recipient` is a payment destination — a UPI id, a card reference, a phone
number. It is not a mailbox, and emailing it would be wrong.

An earlier heuristic sniffed whether `recipient` looked like an email address and fell
back to it when it did not. That was removed: it guessed wrong for phone-number payees
and silently misrouted notifications. The customer's address is now an explicit field on
the event contract.

## Failure handling in the consumer

Both failure paths used to be logged and swallowed, which meant a broken SMTP server lost
the customer's receipt with nothing recorded beyond a log line. That is silent data loss
wearing a log statement as a disguise, so both now propagate and let the container decide.

- **Malformed JSON** — thrown as a non-retryable exception. A poison message is never going
  to parse on a redelivery, so retrying it only delays the traffic behind it. It is routed
  straight to `payment.events.DLT` and the partition continues.
- **Mail failure** — propagated as retryable. The broker already takes the process down if
  the listener keeps returning normally, but doing it explicitly means the retry is visible
  and the attempt budget is enforced rather than incidental.

The trade is deliberate: retrying a mail failure holds the partition until the attempts are
exhausted, so a total SMTP outage slows the topic instead of quietly dropping receipts. When
those attempts run out the event lands in the DLT rather than vanishing. Bounded, visible
loss is preferable to unbounded silent loss.

A null payload is also handled. A compacted or tombstoned record arrives as `null`, and
`ObjectMapper.readValue(null, ...)` throws `IllegalArgumentException` rather than
`JsonProcessingException`, which would escape the catch block and kill the consumer loop.
This was found by writing the test, not by reading the code. A tombstone is acknowledged and
skipped: it is a legitimate record type, not a failure.

## Idempotent consumption, because Kafka is at-least-once

A rebalance mid-processing re-delivers the record, and the customer gets a second receipt.
The consumer now checks a `paymentId + eventType` marker before sending, backed by a Redis
`SET NX` with a seven-day TTL, and writes the marker only after SMTP accepts the message.

Two limits are worth stating plainly. The marker is written after the send, so a crash in
that window still produces one duplicate — closing it would need the marker and the SMTP
call in a single transaction, which does not exist. And the TTL bounds the guarantee: replay
an event from further back than seven days and the consumer will treat it as new. Redis is
the primary store with a per-process fallback, so a Redis outage degrades deduplication
rather than stopping the consumer.

## Service discovery earns its place

Eureka was previously registered by all three services and read by nobody, because there
were no inter-service calls to make. That was the weakest part of the design and it has been
fixed by finding a real reason to need the registry rather than by deleting the registry.

`notification-service` needs the customer's *current* email address and their notification
preference. The payment event carries an email, but that is a snapshot taken when the payment
was created: the customer can change their address or opt out afterwards, and a queued
notification that ignores that is a real bug, not a theoretical one. Current state lives in
`auth-service`, so the notification path calls it by service name and lets Eureka resolve
the instance.

The call goes through Feign, Spring Cloud LoadBalancer and a Resilience4j circuit breaker.
The internal endpoint is guarded by a shared `X-Internal-Token` compared in constant time,
and it fails closed when the token is blank.

The weak link is the shared secret. It is a real limitation, not a disguised one: every
service holds the same bearer token, it travels in a header, and it has no audience, no
expiry and no per-client identity. mTLS or an OAuth2 client-credentials grant would be the
answer in a system with a threat model that justified it. For a single-stack local build it
is the smallest thing that makes the boundary explicit, and the internal path is documented
so it is not mistaken for a user-facing API.

## Caching current state, and failing open on purpose

Preference lookups are cached for 60 seconds. The first version cached for 15 minutes, and a
live test caught the consequence: a customer who opted back in saw no email for up to a
quarter of an hour. 60 seconds bounds that to something a person will not describe as broken.

When `auth-service` is unreachable the resolver falls back to the address in the event and
delivers. That is a deliberate choice, and it is the one to argue about in an interview. A
customer who opts out and then has a notification fail open is a privacy incident. The
defence is that this project models a payment receipt, not a legally binding notice, and a
suppressed receipt is a worse failure than a stale one. Consent that a regulator can cite
would flip this to fail closed, and the code is arranged so that flipping it is a one-line
change in one place.

## Batch publishing without blocking the payment path

The publisher originally sent events one at a time and called `get()` on each future before
starting the next, so a batch cost one broker round trip per event. It also took a
`PESSIMISTIC_WRITE` lock on the batch and held it across those waits, which meant every
payment writing its own outbox row was blocked for the duration.

Three changes, each driven by a measurement rather than a hunch:

- **Pipeline the batch.** All sends are issued first, then the acknowledgements are awaited,
  so a batch costs one round trip instead of N. Each event is judged on its own result
  instead of abandoning the batch on the first failure, which was the head-of-line blocking
  that an earlier version documented as a known weakness.
- **Drop the row lock.** It existed to stop two publisher instances double-sending an event.
  Now that the consumer is idempotent, the race it prevented is absorbed downstream, so the
  payment path is no longer contended. The topic becomes at-least-once, which is the
  guarantee Kafka actually offers; exactly-once was never available here.
- **Index the poll.** `where published_at is null order by created_at` ran as a sequential
  scan and a sort over every row ever written. The `idx_outbox_unpublished` index makes the
  poll read only the unpublished rows. On a table past 10,000 rows this is the difference
  between a scan and an index seek on every tick.

Measured on the compose stack, 1,000 payments, 2,000 outbox events:

| | before | after |
|---|---|---|
| events pending when the burst finished | 1,800 | 366 |
| time to drain the backlog | 31.75s | 1.10s |
| effective drain rate | 56.7/s | 332.7/s |

Backlog after a burst fell by roughly 80% and drain time by 96%. The best observed drain
rate was 2,138 events/sec.

The latency percentiles from this harness are not quoted as a service measurement. The
driver is Python threads with a GIL, and it reported anywhere from 200 to 363 requests/sec
across identical runs with no code change, which is a property of the client, not the
server. At 4-way concurrency the same server returned p50 29.6ms / p99 49.4ms, and the tail
only appeared at 20-way, which is consistent with client-side queueing. A trustworthy
latency number needs a load generator such as k6 or Gatling; neither is available here yet,
so the honest statement is the backlog and drain figures, which are measured server-side.

## Schema management

`spring.jpa.hibernate.ddl-auto=update` is in use. It cannot add a `NOT NULL` column to a
populated table: it silently skipped `user_email`, and every payment query then failed.
Flyway should own the schema. This is a known defect, not a deliberate choice.

## What was tested, and what was not

101 tests across the three services: 27 in auth, 32 in payment, 42 in notification. 98 run
locally; three `AuthIntegrationTests` skip because Testcontainers cannot negotiate with the
Docker engine on the development machine, so those three are unverified.

Covered: idempotency replay, conflicting key reuse, concurrent requests, preference
resolution including opt-out, cache expiry and the fail-open path, internal token
rejection, batch pipelining and per-event failure isolation, owner scoping, both JWT token
lifes in both directions, foreign and tampered signatures, malformed claim handling,
consumer deserialisation including poison messages, null payloads, and deduplication.

Three auth tests need Testcontainers and are skipped on the development machine.

Verified against the running stack, not only in unit tests: the full end-to-end script
including both emails being delivered, all three services registered UP with Eureka, and
the preference flow checked live — opt out produced no email, a missing internal token
returned 401, and opting back in produced exactly one email after the cache expired. A
poison message was published to the real topic and confirmed to land in
`payment.events.DLT`.

Not covered: no load test with a proper load generator, and no fault injection against a
live broker. The "10,000+ concurrent users" figure from earlier resume drafts has no
measurement behind it and was removed. Any capacity claim should come from a benchmark,
not an estimate — the two that do appear in this file are measurements, and the client-side
latency numbers are explicitly not one of them.
