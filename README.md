# QuickPay platform

A digital payment platform built as three Spring Boot microservices. The interesting
problem here is not "accept a payment" — it is that **a client retry must never produce
a second charge**, and **a notification must never be able to affect a payment**.

## Project layout

```text
quickpay/
├── backend/
│   ├── eureka-server/
│   ├── auth-service/
│   ├── payment-service/
│   ├── notification-service/
│   ├── docker-compose.yml
│   └── README.md
├── frontend/
├── .env.example
├── .gitignore
└── README.md
```

## Services

| Service | Folder | Port | Responsibility |
|---|---|---|---|
| auth-service | [`backend/auth-service/`](backend/auth-service/) | 8081 | Identity, JWT issuance, notification preferences |
| payment-service | [`backend/payment-service/`](backend/payment-service/) | 8082 | Payments, idempotency, event publishing |
| notification-service | [`backend/notification-service/`](backend/notification-service/) | 8083 | Kafka consumer, email delivery, dedup |
| eureka-server | [`backend/eureka-server/`](backend/eureka-server/) | 8761 | Service registry |

Plus infrastructure in `backend/docker-compose.yml`: two PostgreSQL instances, Redis, Kafka in
KRaft mode, and Mailpit as a local SMTP sink.

## Architecture

```
                    ┌──────────────┐
    client  ────────▶│ auth-service │  issues access + refresh JWT
                    └──────┬───────┘
                           │ identity travels as a signed JWT, so
                           │ payment-service never calls back
                    ┌──────▼───────┐        ┌──────────────────┐
    client  ────────▶│payment-service│──────▶│ payment.events   │ (Kafka)
      + Bearer      └──────┬────────┘  outbox│                  │
      + Idempotency-Key   │         publish  └────────┬─────────┘
                    ┌────▼─────┐                    │
                    │  Redis   │ idempotency        │ async
                    └──────────┘                    │
                    ┌─────────────┐                  │
                    │ PostgreSQL  │◀── writes ────────┘
                    │ payments +  │    payment and
                    │ outbox      │    outbox in ONE txn
                    └─────────────┘
                                     ┌───────────────────────┐
                                     │ notification-service  │
                                     └────┬─────────────┬────┘
                    current email + pref  │             │ SMTP
                    via Eureka + Feign     │             │
                    (never on the payment  │             │
                     path)  ┌──────────┐  │        ┌────▼──────┐
                            │  Redis   │◀─┘        │  Mailpit  │ :8025
                            │ dedup    │ dedup     └───────────┘
                            └──────────┘
```

**There are two synchronous service-to-service HTTP calls, and only one is on the payment
path.** `notification-service` looks up the customer's *current* email and notification
preference from `auth-service` by service name, resolved through Eureka. A queued event
carries an email snapshot, so without that lookup a customer who changes their address or
opts out would still be notified. That call is not on the payment path — a payment never
waits on it.

The second is `payment-service` → `auth-service`, which resolves a recipient *handle* to
the `ownerId` that owns the wallet to credit. Handle resolution lives in exactly one place
because two spellings of the same handle resolving to two different wallets would send
money to an account that does not exist. It is bounded tightly (1s connect, 1.5s read) and
fails as `503` rather than guessing when auth-service is unreachable. Identity is still
taken from the verified JWT; this call is about routing, not authentication.

## The end-to-end flow

**1. Sign up** — `POST /api/auth/register` on auth-service. Duplicate email or username
is rejected. The password is hashed, the user is saved to `payflow_auth.users`, a random
`ownerId` is assigned, and a token pair is returned.

**2. Log in** — `POST /api/auth/login`. The password hash is compared, `lastLoginAt` is
updated, and a fresh token pair is issued. The access token (15 min) and refresh token
(7 days) both carry `userId`, `ownerId`, `email`, `role` and `type`.

**3. Make a payment** — `POST /payments` with the access token and an
`Idempotency-Key` header. payment-service validates the signature, the expiry, and that
`type == ACCESS`, then reads identity from the claims. It also requires a `handle` claim:
a token issued before that claim existed cannot send money and is refused with a `403`
telling the user to sign in again.

**4. Idempotency (Redis)** — payment-service checks
`payment:idempotency:{ownerId}:{key}`.

| Redis state | Result |
|---|---|
| miss | reserve the key with `SET NX`, continue |
| `PROCESSING` | 409, an identical request is in flight |
| `{fingerprint}:{paymentId}`, fingerprint matches | return the **original** payment |
| `{fingerprint}:{paymentId}`, fingerprint differs | 409, the key was reused |

The fingerprint is SHA-256 over `amount + currency + recipient + description`, so
`500` and `500.00` are recognised as the same request.

**5. The database transaction (PostgreSQL)** — in one transaction: insert the payment,
insert a `PAYMENT_INITIATED` outbox row, set the terminal status, insert a
`PAYMENT_SUCCESS` or `PAYMENT_FAILED` outbox row, commit. Only then does the client get
its response. **The email is not part of the request.**

**6. Publishing (Kafka, outbox pattern)** — a scheduled job polls `payment_outbox` every
second and publishes to `payment.events`, keyed by payment id so both events for one
payment stay ordered. Kafka cannot join a PostgreSQL transaction, so the intent is
written to the database first. This means an event is either published or still waiting
to be retried — never lost, and never sent for a rolled-back payment. The poll reads a
batch, issues every send before awaiting any acknowledgement, and judges each event on its
own result, so one undeliverable event no longer blocks the queue behind it.

**7. Notification** — notification-service consumes the event, ignores
`PAYMENT_INITIATED`, and for a terminal state resolves the customer's current email and
notification preference from auth-service, then sends the email. A Redis marker keyed by
`paymentId + eventType` suppresses a redelivery of the same event.

**8. Failure handling** — malformed payloads and exhausted mail retries are routed to
`payment.events.DLT` instead of being swallowed, so a failure is parked where it can be
inspected rather than lost in a log line.

## Why each technology, and what it costs

| Technology | Why | Cost accepted |
|---|---|---|
| PostgreSQL | Relational data with real constraints and multi-row transactions | Two instances to operate |
| Redis | Fast idempotency check on the write path; sub-millisecond `SET NX` with TTL. Also holds consumer dedup markers | Coordination state is lost if Redis is wiped; a unique index would be atomic inside the existing transaction |
| Kafka | Durable, ordered, replayable log that decouples payment from notification, plus a dead-letter topic | The entire infrastructure cost of a broker to move very few messages. At this volume an in-process call would do |
| Transactional outbox | Makes the event and the payment atomic, which Kafka cannot do alone | A polling job and a table to maintain |
| JWT | Stateless verification with no call back to auth-service on the payment path | Requires a shared secret, which lets a compromised consumer forge tokens |
| Eureka | Service discovery with a real consumer: notification-service resolves `auth-service` to fetch current contact details and consent | A synchronous dependency that did not previously exist, and a registry to operate |
| Resilience4j | Stops an auth-service outage from stalling the Kafka partition | Preference lookups fail open, so a stale opt-out can still produce an email |

### The honest assessment

Three things a reviewer should know before reading the code.

1. **The preference lookup fails open.** If auth-service is unreachable, notification-service
   falls back to the address in the event and sends anyway. For a payment receipt that is the
   right trade — a missing receipt is a worse failure than a slightly stale one — but if this
   carried legally binding consent, the failure mode has to invert. It is one flag in one place.

2. **Kafka is still a lot of machinery for this volume.** The justification is durability and
   replayability, not throughput. Measured, the broker was never the constraint: the original
   bottleneck was the publisher awaiting one broker round trip per event. Pipelining the batch,
   dropping a row lock that blocked payment writes, and indexing the poll query took backlog
   after a 1,000-payment burst from 1,800 events to 366, and drain time from 31.75s to 1.10s.
   The broker was sitting mostly idle throughout.

3. **The internal auth endpoint uses a shared secret.** One `X-Internal-Token`, no audience, no
   expiry, no per-client identity. mTLS or an OAuth2 client-credentials grant is the real answer.
   It fails closed when unset, and it is not exposed as a user-facing API, but it is the
   weakest security component here and it is labelled as such in the code.

See [`DECISIONS.md`](DECISIONS.md) for the full reasoning, including the measurements and
their limits, and each service's README for its own design notes and known limitations.

## Running it

All services and the frontend live in this single repository. Clone it normally and start
the backend stack from the project root:

```bash
git clone <repository-url> quickpay
cd quickpay
docker compose -f backend/docker-compose.yml up -d --build
```

Compose starts infrastructure and all four services with health-gated ordering, and
`kafka-init` creates `payment.events` and `payment.events.DLT` before the producer and
consumer start.

The Compose file requires `AUTH_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `JWT_SECRET`, and
`INTERNAL_API_TOKEN`; it contains no credential defaults. Copy `.env.example` to `.env` and
fill in fresh local values before starting the stack. Use a random JWT secret of at least
32 bytes and use the same internal API token for each service. The `.env` file is ignored
by Git; never commit it or reuse local values in a deployed environment.

```powershell
Copy-Item .env.example .env
```

Open `.env` and set all four values before running Compose.

### Azure demo deployment

The production frontend container serves the built React app and reverse-proxies API
calls through Nginx. To deploy the whole stack to an Ubuntu Azure VM, including private
databases, Redis, Kafka, and SMTP-backed notifications, follow
[`deploy/azure/README.md`](deploy/azure/README.md). This single-VM setup is for
demonstrations only; it has no managed backups or high availability and does not
process real payments.

| Service | URL |
|---|---|
| auth-service | http://localhost:8081 |
| payment-service | http://localhost:8082 |
| notification-service | http://localhost:8083 |
| Eureka dashboard | http://localhost:8761 |
| Mailpit inbox | http://localhost:8025 |
| Frontend (Vite dev) | http://localhost:5173 |

If `docker compose up` fails with `ports are not available` on 5432, a locally installed
Postgres is already using that port. Either stop it, or override the mapping — nothing
depends on the published port, because auth-service reaches the database as
`postgres-auth:5432` over the compose network:

```yaml
# compose.override.yml
services:
  postgres-auth:
    ports: !override
      - "127.0.0.1:5433:5432"
```

Verify:

```bash
curl http://localhost:8761/eureka/apps   # all three should be UP
```

The end-to-end script requires a local test password rather than storing one in the
repository:

```powershell
powershell -ExecutionPolicy Bypass -File ./e2e-test.ps1 -Password (Read-Host "Enter a test-only account password")
```

## Exercising the flow by hand

```bash
# 1. Register — the username IS the payment handle, e.g. "asha"
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"asha","email":"asha@payflow.dev","password":"<choose-a-local-password>"}'

# 2. Log in, copy accessToken from the response
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"asha@payflow.dev","password":"<same-local-password>"}'

# 3. Add money. This is the only way to increase a balance.
curl -X POST http://localhost:8082/wallets/top-up \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":1000,"currency":"USD"}'

# 4. Pay. The recipient is a HANDLE, not an email.
curl -X POST http://localhost:8082/payments \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount":500,"currency":"USD","recipientHandle":"merchant","description":"Lunch"}'

# 5. Read the email at http://localhost:8025
```

Handles are canonicalised the same way on both sides: a leading `@` is dropped and the
rest lowercased, so `@Merchant`, `merchant` and `MERCHANT` are one person. The handle must
match `^[a-z0-9][a-z0-9._]{2,29}$` — 3 to 30 characters, starting with a letter or digit.

Two rules worth knowing before you are surprised by a `400`:

- **A wallet's currency is fixed when the wallet is opened**, and there is no conversion.
  Once a wallet is USD, every top-up and every transfer on it must be USD too.
- **You cannot pay your own handle.**

To see the failure path, top up a small amount and then try to send more than the balance.
That is recorded as a real payment with status `FAILED` and answered with `201`, not as an
error — the payer asked for a transfer and the answer was no, and they are owed a
notification saying so.

To see idempotency, re-send step 3 with the same `Idempotency-Key` and body: you get the
original payment back, not a second charge. Change the amount and you get 409.

To see notification preferences — the reason notification-service calls auth-service — read
the current setting, turn notifications off, then pay again:

```bash
# Check your current preference
curl http://localhost:8081/api/users/me/notification-preferences \
  -H "Authorization: Bearer $ACCESS_TOKEN"

# Opt out, then make a payment: no email arrives
curl -X PUT http://localhost:8081/api/users/me/notification-preferences \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"emailEnabled": false}'
```

Opt back in with `{"emailEnabled": true}` and pay once more. The first email after opting back
in takes up to 60 seconds to appear, because the preference is cached for that long. That
delay is the reason the cache is 60 seconds and not 15 minutes — a quarter of an hour felt
like a broken product.

## Security properties

- Caller identity is derived **only** from verified JWT claims. The service previously
  trusted `X-Authenticated-*` headers, which any caller could forge; that was fixed.
- Refresh tokens are rejected on every API endpoint, so a 7-day credential cannot be
  used as a bearer token.
- Payments are scoped by `ownerId`, so one user cannot read another's payments.
- Login failures do not reveal whether an email is registered.

## Testing

Start the auth service's local test dependencies first, and load the ignored `.env` values
into the current PowerShell session so the Spring Boot tests use the same credentials:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}
docker compose -f backend/docker-compose.yml up -d --wait postgres-auth redis eureka
```

Then run the service and frontend tests from the repository root:

```bash
mvn -f backend/eureka-server/pom.xml test
mvn -f backend/auth-service/pom.xml test
mvn -f backend/payment-service/pom.xml test
mvn -f backend/notification-service/pom.xml test
npm --prefix frontend test
```

The current suites contain 182 tests; 179 run locally and the three auth `AuthIntegrationTests`
are skipped because Testcontainers cannot negotiate with the Docker engine on this
development machine. The Docker API returns a blank `ServerVersion` even though
`docker compose` works against the same daemon. Those three tests remain unverified; CI runs
on every push via GitHub Actions.

Beyond the unit tests, the following was verified against the running Compose stack:

- the full `e2e-test.ps1` flow, including both emails being delivered
- the preference flow, end to end: opt out produced no email, a missing internal token
  returned 401, and opting back in produced exactly one email once the cache expired
- a poison message published to the real topic, confirmed to land in `payment.events.DLT`

## Known gaps

- The outbox publisher retries a failed send forever. There is no attempt counter and no
  producer-side dead-letter, so an event the broker permanently refuses sits at the front of
  every poll. The consumer side has a DLT; the producer side does not.
- Preference lookups fail open, so a stale opt-out can still produce an email for up to the
  60-second cache lifetime plus any auth-service outage.
- The internal auth endpoint authenticates with a shared secret rather than per-client
  credentials.
- `spring.jpa.hibernate.ddl-auto=update` cannot add a `NOT NULL` column to a populated
  table. Flyway should own the schema.
- Shared symmetric JWT secret; no refresh rotation or revocation.
- No load test with a proper load generator, so there is no trustworthy service-side latency
  figure. The throughput numbers in `DECISIONS.md` are backlog and drain measurements, which
  are unambiguous, not client-side latency percentiles.
- The frontend has unit tests for the client-side logic but no component or end-to-end suite.
  See [`frontend/README.md`](frontend/README.md).

## Frontend

[`frontend/`](frontend/) holds the React client. Start it after the
stack is up:

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

It talks to the services through the Vite dev proxy rather than directly, because the
services' CORS configuration is restrictive and notification-service has none at all.
`frontend/README.md` covers the proxy, the scripts and how the client models the backend.
