/**
 * Handle rules mirrored from auth-service's HandleService.
 *
 * The backend canonicalises a handle before storing or looking one up: a leading "@"
 * is dropped and the rest is lowercased, then it must match ^[a-z0-9][a-z0-9._]{2,29}$.
 * Doing the same conversion client-side means "@Sai123" and "sai123" are treated as
 * the same person before a request is sent, instead of producing a 400 from the
 * service and leaving the user to guess which spelling was wrong.
 */

/** Must match HandleService.VALID. */
const HANDLE_PATTERN = /^[a-z0-9][a-z0-9._]{2,29}$/

export const HANDLE_RULE =
  'Handles are 3 to 30 characters: letters, digits, dot or underscore, starting with a letter or digit.'

/** Canonical form, or null when the input cannot be a handle. */
export function normalizeHandle(input: string): string | null {
  const trimmed = input.trim()
  const withoutPrefix = trimmed.startsWith('@') ? trimmed.slice(1) : trimmed
  const canonical = withoutPrefix.toLowerCase()
  return HANDLE_PATTERN.test(canonical) ? canonical : null
}

/**
 * The form to send to payment-service. It accepts the "@name" spelling too
 * (@Size(max = 31) allows one prefix character), so sending the canonical handle is
 * always safe and removes the ambiguity.
 */
export function toRecipientHandle(input: string): string {
  return normalizeHandle(input) ?? input.trim()
}

/** PaymentResponse.recipientHandle is stored canonical, so this only adds the "@". */
export function displayHandle(handle: string): string {
  return handle ? `@${handle}` : '—'
}