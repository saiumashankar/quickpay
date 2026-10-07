/**
 * Types mirroring the backend DTOs exactly.
 * Sources: auth-service (8081), payment-service (8082), notification-service (8083).
 */

export type Role = 'USER' | 'ADMIN'
export type PaymentStatus = 'PENDING' | 'SUCCESS' | 'FAILED'
export type UserStatus = 'ACTIVE' | 'INACTIVE'

/** Role — auth-service entity/Role.java */
export interface UserResponse {
  id: number
  ownerId: string
  /** Canonical handle, e.g. "sai123". What somebody types to pay this user. */
  username: string
  email: string
  role: Role
  status: UserStatus
  createdAt: string
}

/** AuthResponse — auth-service dto/AuthResponse.java */
export interface AuthResponse {
  accessToken: string
  refreshToken: string
  /** Always "Bearer" today, but sent as `Authorization: <tokenType> <accessToken>`. */
  tokenType: string
  /** Instant, ISO-8601. Access tokens are configured for 15 minutes. */
  expiresAt: string
  user: UserResponse
}

/** RegisterRequest — the username field is a handle, not a display name. */
export interface RegisterRequest {
  username: string
  email: string
  password: string
}

/** LoginRequest — email only. The handle is not an accepted credential. */
export interface LoginRequest {
  email: string
  password: string
}

/** RefreshTokenRequest */
export interface RefreshTokenRequest {
  refreshToken: string
}

/** PaymentResponse — payment-service dto/PaymentResponse.java */
export interface PaymentResponse {
  id: string
  userId: number
  /** Caller's own handle, copied out of the JWT by payment-service. */
  senderHandle: string
  amount: number
  currency: string
  /** The recipient's handle, not an email or an account number. */
  recipientHandle: string
  /** Whether this account sent or received the transfer. */
  direction: 'SENT' | 'RECEIVED'
  description: string | null
  status: PaymentStatus
  createdAt: string
}

/** CreatePaymentRequest — amount is @Digits(integer = 15, fraction = 4), min 0.01. */
export interface CreatePaymentRequest {
  amount: number
  /** Must match [A-Z]{3} exactly, or the service returns 400. */
  currency: string
  /** @Size(max = 31): one leading "@" plus up to 30 handle characters. */
  recipientHandle: string
  /** @Size(max = 500) */
  description?: string
}

/**
 * WalletResponse — payment-service dto/WalletResponse.java.
 *
 * Every nullable field here is null for a wallet the caller has never opened, which
 * WalletService.opening() returns instead of a 404: not being able to send money
 * because you have never received any is the same user-visible state as a zero
 * balance, so it must not read as a missing wallet.
 *
 * In particular `currency` is null until the wallet is opened, and then it is fixed
 * for good — the service refuses to convert.
 */
export interface WalletResponse {
  ownerId: string
  handle: string | null
  balance: number
  currency: string | null
  createdAt: string | null
}

/** TopUpRequest — currency is optional and defaults to USD server-side. */
export interface TopUpRequest {
  amount: number
  currency?: string
}

/** NotificationPreferencesResponse — served by auth-service for the signed-in user. */
export interface NotificationPreferences {
  userId: number
  ownerId: string
  email: string
  emailEnabled: boolean
}

/** PaymentEventType — notification-service model/PaymentEventType.java */
export type PaymentEventType = 'PAYMENT_INITIATED' | 'PAYMENT_SUCCESS' | 'PAYMENT_FAILED'

/**
 * PaymentEvent — the body POST /notify/test accepts. Only the sender side is
 * populated for a manual test: recipientOwnerId and recipientEmail stay null
 * because a hand-made event has no real second party.
 */
export interface PaymentEvent {
  eventType: PaymentEventType
  paymentId: string
  userId: number
  ownerId: string
  userEmail: string
  senderHandle: string
  amount: number
  currency: string
  recipient: string
  recipientOwnerId: string | null
  recipientEmail: string | null
  description: string | null
  merchantWebhookUrl: string | null
  status: PaymentStatus
  occurredAt: string
}

/** RFC 7807 — returned by payment-service (8082) and notification-service (8083). */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
}

/** auth-service (8081) returns `{ message }`, or a flat field->message map on validation errors. */
export interface MessageError {
  message?: string
}

/**
 * Mailpit message summary (8025), as returned by GET /api/v1/messages.
 *
 * Mailpit keeps capitalised keys and exposes a single `Address` per party, unlike
 * MailHog. The collection is `messages`.
 */
export interface MailpitMessage {
  ID: string
  Subject: string
  From: { Name: string; Address: string }
  To: { Name: string; Address: string }[]
  Created: string
  Size: number
}

export interface MailpitListing {
  total: number
  messages: MailpitMessage[]
}