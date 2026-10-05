import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'

import {
  ApiError,
  createPayment,
  getWallet,
  listMailpitMessages,
  listPayments,
  setUnauthorizedHandler,
  topUpWallet,
  updateNotificationPreferences,
} from './api.ts'
import type { UserResponse } from './types.ts'

/**
 * The three services answer errors in three different shapes, so these tests stub
 * fetch rather than calling a live service. What is being protected is the mapping
 * from each shape onto a message a user can act on, plus the headers the service
 * insists on.
 */

interface Captured {
  url: string
  method: string
  headers: Record<string, string>
  body: unknown
}

const realFetch = globalThis.fetch
let captured: Captured | null = null

/** Replies with the given status and body, recording what was sent. */
function stubFetch(status: number, body: unknown): void {
  captured = null
  globalThis.fetch = (async (input: string, init: RequestInit) => {
    captured = {
      url: input,
      method: init.method ?? 'GET',
      headers: (init.headers ?? {}) as Record<string, string>,
      body: init.body === undefined ? undefined : JSON.parse(String(init.body)),
    }
    return new Response(body === undefined ? '' : JSON.stringify(body), { status })
  }) as typeof fetch
}

afterEach(() => {
  globalThis.fetch = realFetch
  captured = null
  setUnauthorizedHandler(null)
})

describe('request headers', () => {
  it('sends the bearer token on a protected call', async () => {
    stubFetch(200, { balance: 10 })
    await getWallet('token-abc')
    assert.equal(captured?.headers.Authorization, 'Bearer token-abc')
  })

  it('sends the mandatory Idempotency-Key header when creating a payment', async () => {
    // PaymentController takes @RequestHeader("Idempotency-Key"); without it the
    // request is rejected before the body is even read.
    stubFetch(201, { id: 'p1', status: 'SUCCESS' })
    await createPayment(
      'token-abc',
      { amount: 10, currency: 'USD', recipientHandle: 'bobpay1' },
      'key-123',
    )
    assert.equal(captured?.headers['Idempotency-Key'], 'key-123')
  })

  it('reads transfer history from /payments/me, not the bare collection', async () => {
    stubFetch(200, [])
    await listPayments('token-abc')
    assert.equal(captured?.url, '/payments/me')
  })

  it('puts emailEnabled in the body of the preferences update', async () => {
    stubFetch(200, { emailEnabled: false })
    await updateNotificationPreferences('token-abc', false)
    assert.deepEqual(captured?.body, { emailEnabled: false })
    assert.equal(captured?.method, 'PUT')
  })

  it('sends JSON only when there is a body', async () => {
    stubFetch(200, { balance: 0 })
    await getWallet('token-abc')
    assert.equal(captured?.headers['Content-Type'], undefined)
  })

  it('passes the request body through unchanged', async () => {
    // Pruning a blank description is the caller's job, not this layer's: PaymentPage
    // omits the field entirely, so the backend sees a missing optional field rather
    // than an empty string. Both produce the same idempotency fingerprint, so the
    // distinction is cosmetic, and api.ts stays a thin transport.
    stubFetch(201, { id: 'p1', status: 'SUCCESS' })
    await createPayment(
      'token-abc',
      { amount: 10, currency: 'USD', recipientHandle: 'sai123', description: 'Lunch' },
      'key-123',
    )
    assert.deepEqual(captured?.body, {
      amount: 10,
      currency: 'USD',
      recipientHandle: 'sai123',
      description: 'Lunch',
    })
  })
})

describe('error shapes', () => {
  it('prefers the RFC 7807 detail from payment-service', async () => {
    stubFetch(409, { status: 409, detail: 'Idempotency key was already used for a different request' })
    const error = await createPayment(
      'token-abc',
      { amount: 10, currency: 'USD', recipientHandle: 'bobpay1' },
      'key-123',
    ).catch((caught: unknown) => caught)

    assert.ok(error instanceof ApiError)
    assert.equal(error.status, 409)
    assert.equal(error.message, 'Idempotency key was already used for a different request')
  })

  it('reads the { message } shape from auth-service', async () => {
    stubFetch(400, { message: 'Invalid email or password' })
    const error = await updateNotificationPreferences('token-abc', true).catch((c: unknown) => c)
    assert.ok(error instanceof ApiError)
    assert.equal(error.message, 'Invalid email or password')
  })

  it('turns a flat field-to-message validation map into per-field errors', async () => {
    // This is exactly what auth-service's MethodArgumentNotValidException handler
    // returns, and it is what lets the form show "Email should be valid" under the
    // email input instead of one opaque banner.
    stubFetch(400, {
      username: 'Username must be between 3 and 30 characters',
      email: 'Email should be valid',
    })
    const error = await topUpWallet('token-abc', { amount: 0 }).catch((c: unknown) => c)

    assert.ok(error instanceof ApiError)
    assert.deepEqual(error.fields, {
      username: 'Username must be between 3 and 30 characters',
      email: 'Email should be valid',
    })
  })

  it('explains an unreachable backend when a 5xx arrives with no body', async () => {
    // The dev proxy answers with a bare 5xx when it cannot reach the service.
    stubFetch(502, undefined)
    const error = await listPayments('token-abc').catch((c: unknown) => c)

    assert.ok(error instanceof ApiError)
    assert.match(error.message, /Cannot reach the backend/)
  })

  it('reports an expired session when the backend answers 401', async () => {
    stubFetch(401, { detail: 'Authentication required' })
    const error = await listMailpitMessages().catch((c: unknown) => c)
    assert.ok(error instanceof ApiError)
    assert.equal(error.status, 401)
  })
})

describe('unauthorized handling', () => {
  it('signs the user out when a protected call comes back 401', async () => {
    let signedOut = false
    setUnauthorizedHandler(() => {
      signedOut = true
    })
    stubFetch(401, { detail: 'Authentication required' })
    await listPayments('token-abc').catch(() => undefined)
    assert.equal(signedOut, true)
  })

  it('does not sign the user out when the 401 is from a login attempt', async () => {
    // A 401 on the login route means "wrong password". Treating it as an expired
    // session would wipe the form the user is still filling in.
    let signedOut = false
    setUnauthorizedHandler(() => {
      signedOut = true
    })
    stubFetch(401, { message: 'Invalid email or password' })
    const { login } = await import('./api.ts')
    await login({ email: 'a@b.com', password: 'wrong' }).catch(() => undefined)
    assert.equal(signedOut, false)
  })
})

describe('sendTestNotification body', () => {
  it('sends the signed-in user rather than placeholder zeros', async () => {
    const user: UserResponse = {
      id: 7,
      ownerId: 'owner-uuid',
      username: 'saitest1',
      email: 'saitest1@payflow.dev',
      role: 'USER',
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00Z',
    }
    stubFetch(200, { status: 'processed', paymentId: 'x' })
    const { sendTestNotification } = await import('./api.ts')
    await sendTestNotification('token-abc', 'PAYMENT_SUCCESS', user, 25)

    const body = captured?.body as Record<string, unknown>
    assert.equal(body.userId, 7)
    assert.equal(body.ownerId, 'owner-uuid')
    assert.equal(body.userEmail, 'saitest1@payflow.dev')
    assert.equal(body.senderHandle, 'saitest1')
    assert.equal(body.status, 'SUCCESS')
    // A manual test has no real second party, so the recipient stays null and the
    // recipient is simply not notified.
    assert.equal(body.recipientOwnerId, null)
    assert.equal(body.recipientEmail, null)
  })
})