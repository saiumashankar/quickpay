import type {
  AuthResponse,
  CreatePaymentRequest,
  LoginRequest,
  MailpitListing,
  NotificationPreferences,
  PaymentEvent,
  PaymentEventType,
  PaymentResponse,
  ProblemDetail,
  RefreshTokenRequest,
  RegisterRequest,
  TopUpRequest,
  UserResponse,
  WalletResponse,
} from './types'

/**
 * All backend calls are relative so they go through the Vite dev proxy, which makes
 * them same-origin and avoids the services' CORS restrictions (see vite.config.ts).
 *
 * The three services share a path namespace with no overlap, so a single relative
 * path per call is enough:
 *   auth-service:8081        /api/auth/*, /api/users/*
 *   payment-service:8082     /payments*, /wallets*
 *   notification-service:8083 /notify/*
 */

const BASE = ''

export class ApiError extends Error {
  readonly status: number
  /** Per-field messages from auth-service's validation handler, when present. */
  readonly fields: Record<string, string>

  constructor(message: string, status: number, fields: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fields = fields
  }
}

/**
 * Pulls the most useful message out of whichever error shape came back:
 * RFC 7807 `detail` (8082/8083), `{ message }`, or a flat field->message map (8081).
 */
function toError(status: number, body: unknown): ApiError {
  // The dev proxy answers with a bare 5xx when it cannot reach the upstream service,
  // so a bodyless 500 means "the backend is down", not "the request was rejected".
  const hasStructuredBody = body !== null && typeof body === 'object'

  if (body && typeof body === 'object') {
    const problem = body as ProblemDetail
    if (typeof problem.detail === 'string' && problem.detail) {
      return new ApiError(problem.detail, status)
    }
    const messageError = body as { message?: unknown }
    if (typeof messageError.message === 'string' && messageError.message) {
      return new ApiError(messageError.message, status)
    }
    // Validation failures come back as a flat map, e.g. { email: "Email should be valid" }.
    const entries = Object.entries(body as Record<string, unknown>)
    if (entries.length && entries.every(([, v]) => typeof v === 'string')) {
      const fields = Object.fromEntries(entries) as Record<string, string>
      return new ApiError(Object.values(fields).join(' '), status, fields)
    }
  }

  if (!hasStructuredBody && status >= 500) {
    return new ApiError(
      'Cannot reach the backend. Start the services with "docker compose -p payflow -f docker-compose.yml up -d".',
      status,
    )
  }
  if (status === 401) return new ApiError('Your session has expired. Please sign in again.', status)
  if (status === 403) return new ApiError('You do not have access to that resource.', status)
  if (status === 404) return new ApiError('That endpoint was not found on the server.', status)
  return new ApiError(`Request failed with status ${status}.`, status)
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT'
  token?: string | null
  headers?: Record<string, string>
  body?: unknown
  /**
   * Set for the login/register calls themselves. A 401 there means "wrong password",
   * not "your token expired", so it must not trigger the global sign-out.
   */
  skipUnauthorizedHandler?: boolean
}

/**
 * Called whenever an authenticated request comes back 401, so the rejected JWT can be
 * discarded and the user returned to the start page. Registered by useSession.
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', token, headers = {}, body, skipUnauthorizedHandler = false } = options

  const finalHeaders: Record<string, string> = { ...headers }
  if (body !== undefined) finalHeaders['Content-Type'] = 'application/json'
  if (token) finalHeaders.Authorization = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
      method,
      headers: finalHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError('Unable to reach the server. Is the backend running?', 0)
  }

  if (response.status === 204) return undefined as T

  let payload: unknown = null
  const text = await response.text()
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }

  if (!response.ok) {
    // The backend validates the JWT on every protected route. A 401 means the token
    // is expired, malformed or revoked, so the session cannot continue.
    if (response.status === 401 && !skipUnauthorizedHandler && token) {
      unauthorizedHandler?.()
    }
    throw toError(response.status, payload)
  }
  return payload as T
}

/* ------------------------------------------------------------------ auth (8081) */

export function register(input: RegisterRequest): Promise<AuthResponse> {
  return request<AuthResponse>('/api/auth/register', {
    method: 'POST',
    body: input,
    skipUnauthorizedHandler: true,
  })
}

export function login(input: LoginRequest): Promise<AuthResponse> {
  return request<AuthResponse>('/api/auth/login', {
    method: 'POST',
    body: input,
    skipUnauthorizedHandler: true,
  })
}

/**
 * Exchanges a refresh token for a fresh pair. The route is permitAll, so it must not
 * carry the access token: a 401 here means the refresh token itself is dead, and
 * letting the global handler fire on that would sign the user out without trying.
 */
export function refreshSession(refreshToken: string): Promise<AuthResponse> {
  const body: RefreshTokenRequest = { refreshToken }
  return request<AuthResponse>('/api/auth/refresh', {
    method: 'POST',
    body,
    skipUnauthorizedHandler: true,
  })
}

/** The signed-in user, as auth-service currently sees them. */
export function getCurrentUser(token: string): Promise<UserResponse> {
  return request<UserResponse>('/api/users/me', { token })
}

export function getNotificationPreferences(token: string): Promise<NotificationPreferences> {
  return request<NotificationPreferences>('/api/users/me/notification-preferences', { token })
}

export function updateNotificationPreferences(
  token: string,
  emailEnabled: boolean,
): Promise<NotificationPreferences> {
  return request<NotificationPreferences>('/api/users/me/notification-preferences', {
    method: 'PUT',
    token,
    body: { emailEnabled },
  })
}

/* --------------------------------------------------------------- payments (8082) */

/** `Idempotency-Key` is mandatory on this endpoint — the service rejects calls without it. */
export function createPayment(
  token: string,
  input: CreatePaymentRequest,
  idempotencyKey: string,
): Promise<PaymentResponse> {
  return request<PaymentResponse>('/payments', {
    method: 'POST',
    token,
    headers: { 'Idempotency-Key': idempotencyKey },
    body: input,
  })
}

/** The caller's own transfer history. `/payments/me` rather than the bare collection. */
export function listPayments(token: string): Promise<PaymentResponse[]> {
  return request<PaymentResponse[]>('/payments/me', { token })
}

export function getPayment(token: string, id: string): Promise<PaymentResponse> {
  return request<PaymentResponse>(`/payments/${id}`, { token })
}

/* --------------------------------------------------------------- wallets (8082) */

/** The caller's own wallet. A wallet never opened yet comes back as a zero balance. */
export function getWallet(token: string): Promise<WalletResponse> {
  return request<WalletResponse>('/wallets/me', { token })
}

/** Adds funds to the caller's own wallet. There is no endpoint to set a balance. */
export function topUpWallet(token: string, input: TopUpRequest): Promise<WalletResponse> {
  return request<WalletResponse>('/wallets/top-up', {
    method: 'POST',
    token,
    body: input,
  })
}

/* ----------------------------------------------------------- notifications (8083) */

/**
 * Sends a test notification. The service always answers 200 once authenticated, and
 * only delivers mail for SUCCESS/FAILED events, so callers must not treat this as
 * confirmation that an email was sent.
 */
export function sendTestNotification(
  token: string,
  eventType: PaymentEventType,
  user: UserResponse,
  amount: number,
): Promise<{ status: string; paymentId: string }> {
  const succeeded = eventType === 'PAYMENT_SUCCESS'
  const event: PaymentEvent = {
    eventType,
    paymentId: crypto.randomUUID(),
    userId: user.id,
    ownerId: user.ownerId,
    userEmail: user.email,
    senderHandle: user.username,
    amount,
    currency: 'USD',
    recipient: 'manual-test-recipient',
    // A manual test has no real second party, so the recipient fields stay null and
    // the recipient is simply not notified.
    recipientOwnerId: null,
    recipientEmail: null,
    description: 'manual test notification',
    merchantWebhookUrl: null,
    status: succeeded ? 'SUCCESS' : 'FAILED',
    occurredAt: new Date().toISOString(),
  }
  return request<{ status: string; paymentId: string }>('/notify/test', {
    method: 'POST',
    token,
    body: event,
  })
}

/* ---------------------------------------------------------------- Mailpit (8025) */

/**
 * The backend has no "list notifications" endpoint — notification-service keeps no
 * record of what it sent. Mailpit, the local SMTP sink, is where the delivered emails
 * actually land, so it is the only source of real notification data.
 */
export function listMailpitMessages(): Promise<MailpitListing> {
  return request<MailpitListing>('/mailpit/api/v1/messages')
}