# QuickPay frontend

The React client for the QuickPay platform: a strict sequence of **start → register →
login → overview → payment → notifications**, driven entirely by a `stage` value in
`src/lib/session.ts`.

Registration creates the account but deliberately does **not** sign in. The backend
returns a token from `POST /api/auth/register`, but the flow discards it and sends the
user to login, so the JWT that authorises payments is always one the user actually
proved they own by signing in.

Login lands on the **overview**, not the payment form. Signing in and sending money are
different intents, so the overview is the signed-in home — balance, recent activity,
wallet top-up, and clear actions for making a payment or managing notifications. The
Transactions page contains the full payment history.

The profile menu on signed-in pages opens a dedicated **Transactions** view with payment
record count, completed/pending/unsuccessful counts, successful totals grouped by currency,
and the full payment history from payment-service.

## Running it

The frontend needs the backend running. Clone the root project, then use two terminals
from the root project directory:

```bash
docker compose -f backend/docker-compose.yml up -d --build
```

In another terminal:

```bash
cd frontend
npm ci
npm run dev
```

Then open **http://localhost:5173**.

Verify the backend is up before blaming the frontend:

```bash
curl http://localhost:8761/eureka/apps   # all three services should be registered
```

| URL | What |
|---|---|
| http://localhost:5173 | this frontend |
| http://localhost:8081 | auth-service |
| http://localhost:8082 | payment-service |
| http://localhost:8083 | notification-service |
| http://localhost:8761 | Eureka dashboard |
| http://localhost:8025 | Mailpit — where the receipt emails actually land |

### Why there is a dev proxy

Every backend call in `src/lib/api.ts` is a **relative** path, so it goes through the
Vite dev proxy rather than to `localhost:8081` and friends directly. This is not just
convenience:

- The services' CORS configuration is restrictive — payment-service allows only
  `localhost:3000` — and **notification-service has no CORS configuration at all**, so
  a dev server on `:5173` is rejected outright.
- Proxying alone is not sufficient. `http-proxy` forwards the browser's
  `Origin: http://localhost:5173` verbatim, and Spring's `CorsFilter` rejects that
  before the request is ever handled. `vite.config.ts` therefore rewrites `Origin` to an
  allow-listed value, which makes the backend treat the call as permitted while the
  browser still sees it as same-origin.

**This only fixes local development.** A production build served from its own origin
needs that origin added to the allowed origins in the backend security configs, and a
CORS config added to notification-service, which has none.

| Proxy prefix | Target |
|---|---|
| `/api` | auth-service `:8081` — `/api/auth/*`, `/api/users/*` |
| `/payments` | payment-service `:8082` |
| `/wallets` | payment-service `:8082` |
| `/notify` | notification-service `:8083` |
| `/mailpit` | Mailpit `:8025` |

## Scripts

```bash
npm run dev         # dev server on :5173
npm run build       # production bundle into dist/
npm run preview     # serve the built bundle on :4173
npm run typecheck   # tsc --noEmit
npm test            # unit tests
```

## Tests

33 unit tests over the logic that has to agree with the backend and is easy to get
subtly wrong:

| File | Covers |
|---|---|
| `src/lib/handles.test.ts` | handle canonicalisation, mirroring `HandleService` in auth-service |
| `src/lib/money.test.ts` | balance and amount formatting, including a wallet's `null` currency |
| `src/lib/api.test.ts` | request headers, and the three different error shapes the services return |

They run on Node's built-in test runner with native TypeScript stripping
(`node --experimental-strip-types`), so there is no test framework to install and
nothing new in `devDependencies` beyond `@types/node`. The `.ts` extensions in the test
imports are required by that loader; `tsconfig.json` sets `allowImportingTsExtensions`
so `npm run typecheck` still covers them.

There is no component or end-to-end suite. The pages were verified by rendering them in
a headless browser against the live stack, but that is a manual check and nothing guards
it against regression yet.

## How the client models the backend

A few decisions that are not obvious from the code alone.

**A handle is not an email.** The recipient of a payment is a handle — the thing people
type to pay you — canonicalised the same way `HandleService` does it: a leading `@` is
dropped, the rest lowercased, then matched against `^[a-z0-9][a-z0-9._]{2,29}$`.
`src/lib/handles.ts` mirrors that rule so a typo fails on the client with a readable
message instead of arriving as a `400`.

**One currency, chosen once.** A wallet's currency is fixed when the wallet is opened,
and payment-service **refuses to convert** — paying in a different currency is a `400`,
not an exchange. Enter any three-letter ISO currency code when topping up an unopened
wallet; payments automatically use the wallet's currency and do not offer a currency
switch. An already-opened wallet cannot be changed to another currency without backend
conversion support.

**A wallet that was never opened is not an error.** `WalletService.view` returns a
zero balance with `handle`, `currency` and `createdAt` all `null` rather than a `404`,
because "you have never received money" and "you have no money" are the same thing to a
user. Every balance is therefore formatted through `src/lib/money.ts`, which never
throws on a `null` currency — `Intl.NumberFormat` throws a `RangeError` on one.

**Insufficient funds is a `200`.** A transfer the payer cannot afford is recorded as a
real payment with status `FAILED`, not raised as an error, so the UI keys off
`payment.status` rather than the HTTP status.

**The receipt inbox filters to you.** `notification-service` emails *both* parties of a
transfer, and Mailpit holds every message the stack has ever sent rather than a
per-user mailbox. The notifications page filters to the address auth-service currently
sends to, so another account's receipts never appear in your inbox.

**Three error shapes, one `ApiError`.** auth-service returns `{ message }` or a flat
`field → message` map on validation failure; payment-service and notification-service
return RFC 7807 `detail`. `toError` in `src/lib/api.ts` maps all three onto a message
you can act on, and turns the flat map into per-field messages for the form.

**The session renews itself.** Access tokens last 15 minutes and refresh tokens 7 days,
so the session is kept past access-token expiry and renewed in the background through
`POST /api/auth/refresh` about a minute before it lapses. Only a rejected refresh token
signs you out.

## Layout

```
src/
  App.tsx                     routes on `stage`
  components/
    StartPage.tsx             marketing entry point
    AuthForm.tsx              register and login
    DashboardPage.tsx         overview: balance, transfers, preference, payment CTA
    PaymentPage.tsx           make a payment
    NotificationsPage.tsx     receipt, inbox, notification preference
    TopUpCard.tsx             add money, shared by the overview and payment page
    ProfileMenu.tsx           account menu; signing out is a step inside it
    StatusBadge.tsx           PENDING / SUCCESS / FAILED
  lib/
    api.ts                    every backend call, and error mapping
    session.ts                stage machine, token storage and renewal
    types.ts                  mirrors of the backend DTOs
    handles.ts                handle canonicalisation
    money.ts                  null-safe amount formatting
```