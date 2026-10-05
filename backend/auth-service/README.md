# QuickPay — auth-service

Identity and token issuance. Registers users, verifies passwords, and issues the JWT
pair that every other service trusts.

Part of the QuickPay microservices system. The root project contains the shared
architecture, Docker Compose stack, Eureka server, and frontend.

## Stack

Java 21, Spring Boot 3.3, Spring Security, PostgreSQL, Redis, JJWT, Eureka client,
Testcontainers, Docker.

## Responsibilities

- Register a user, rejecting duplicate email or username
- Authenticate an email and password, updating the login timestamp on success
- Issue a short-lived access token and a long-lived refresh token
- Refresh a token pair from a valid refresh token
- Serve the caller's own profile, cached in Redis
- Own the current email address and notification preference, and serve them to
  notification-service over a token-guarded internal endpoint

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/auth/register` | none | Create an account, returns a token pair |
| `POST` | `/api/auth/login` | none | Exchange credentials for a token pair |
| `POST` | `/api/auth/refresh` | refresh token | Mint a new token pair |
| `GET` | `/api/users/me` | Bearer | The caller's profile |
| `GET` | `/api/users/me/notification-preferences` | Bearer | The caller's email notification setting |
| `PUT` | `/api/users/me/notification-preferences` | Bearer | Turn email notifications on or off |
| `GET` | `/api/internal/users/{ownerId}/notification-preferences` | `X-Internal-Token` | Service-to-service lookup, **not** for clients |
| `GET` | `/actuator/health` | none | Liveness and readiness |

## Notification preferences

auth-service owns the current email address and whether the customer wants email
notifications. notification-service needs both at the moment it sends, which is after the
payment event was written and possibly after the customer has changed their mind — so it
looks them up rather than trusting the snapshot in the event.

`notificationPreferencesEnabled` is a **nullable** Boolean. Existing rows are null, and null
is treated as enabled, so registering a user before this feature existed does not silently
mute their receipts. A real preference needs three states: opted in, opted out, and never
asked.

The user-facing endpoints use the same Bearer authentication as the rest of the API and only
ever touch the caller's own record. The internal endpoint is a separate surface, guarded by
`InternalTokenFilter`:

- the token is compared in constant time, so the comparison does not leak its value by timing
- a blank configured token **fails closed**: with no token set the internal API returns 401
  rather than becoming open to anything that can reach the port
- it is scoped to one read-only lookup, so a leaked token cannot register, log in, or change
  a password

The shared secret is the weakest security component in this project and is labelled as such.
It is one bearer token with no audience, no expiry and no per-client identity, so a compromise
gives no way to tell which service leaked it. mTLS, or an OAuth2 client-credentials grant
where each service holds its own client secret, is the real answer; this is the smallest
thing that makes the service-to-service boundary explicit.

## Design decisions worth explaining in an interview

**Two token lifetimes.** The access token lasts 15 minutes and the refresh token 7 days.
A leaked access token is therefore bounded, while the user is not forced to log in
every quarter hour. Both tokens carry a `type` claim, which is what lets the downstream
services reject a refresh token used as a bearer credential.

**`ownerId` is a permanent public identifier.** Each user gets a random UUID at
registration, independent of the database id. Payment records are scoped by `ownerId`,
so ownership does not leak sequential primary keys and survives data migration.

**Claims are the contract.** The token carries `sub` (email), `userId`, `ownerId`,
`role` and `type`. payment-service validates locally with the shared secret and
reads identity from the verified claims, so **no service-to-service call is needed to
authenticate a request**. That is the whole point of JWT, and it is why a payment never
waits on this service.

The one exception is notification-service looking up a customer's current contact
details, which is data rather than authentication. It happens off the payment path, so
adding it did not put a call in the critical write path.

**Login failures do not reveal whether an account exists.** An unknown email and a
wrong password both surface as `Invalid email or password`, which avoids leaking which
emails are registered.

**The profile cache is namespaced and short-lived.** `auth:user-profile:{id}` with a
10-minute TTL. A profile change can therefore be stale for up to 10 minutes, which is
an accepted trade for avoiding a database round trip on every profile read. Changing a
notification preference evicts the entry rather than waiting the TTL out, because a stale
"opted out" is the one staleness a customer would actually complain about.

## Testing

```bash
mvn test
```

40 tests, including three Testcontainers integration tests that skip on this machine:

- `TokenManagerTest` — claim names and values, both token types, the 15-minute and
  7-day lifetimes, cross-type rejection in both directions, foreign signature rejection,
  tampered payload, garbage input
- `RedisCacheTest` — cache miss, full round trip, namespaced key, 10-minute TTL
- `NotificationPreferencesTest` — read and update the caller's own preference, null treated
  as enabled, another user's preference not reachable
- `InternalTokenFilterTest` — correct token accepted, missing token rejected, wrong token
  rejected, blank configured token fails closed
- `AuthIntegrationTests` — Testcontainers-backed register, login, protected profile,
  duplicate registration, and method-not-allowed

37 tests run locally and 3 are skipped.

The three `AuthIntegrationTests` skip because Testcontainers cannot negotiate with the
Docker engine on the machine this was developed on — the Docker API returns a blank
`ServerVersion` and an empty container list even though `docker compose` works normally
against the same daemon. So these three are **unverified**, not merely deferred to CI. Their
Dockerfile and `@Testcontainers` wiring follow the documented pattern and should be the first
thing to check on a machine where `docker version` reports a real server.

## Known limitations

- The internal service-to-service API authenticates with one shared `X-Internal-Token`
  rather than per-client credentials. No audience, no expiry, no way to attribute a leak.
- The JWT secret is shared symmetrically across all three services, so a compromised
  consumer could mint tokens. RS256 with per-service keys would close this and is
  contained to two `SecurityConfig` methods.
- There is no refresh-token rotation or revocation list, so a stolen refresh token is
  valid for its full 7 days and logout is client-side only.
- No rate limiting on `login` or `register`, leaving both open to credential stuffing.
- No account lockout or email verification step.
- `INTERNAL_API_TOKEN` has a local-development default in `backend/docker-compose.yml` so the stack runs
  out of the box. That is a local-development convenience and must be replaced anywhere
  real.
