/**
 * Money formatting for values that come back from payment-service.
 *
 * A wallet that has never been opened reports `currency: null`, and
 * Intl.NumberFormat throws a RangeError on a null or unknown currency rather than
 * falling back. Formatting therefore has to be total: every call site renders a
 * balance that may legitimately be unknown.
 */
const supportedCurrencyValues = (
  Intl as typeof Intl & { supportedValuesOf?: (key: 'currency') => string[] }
).supportedValuesOf?.('currency')
const supportedCurrencyCodes = supportedCurrencyValues
  ? new Set(supportedCurrencyValues)
  : null

/** Falls back to a neutral currency code so the formatter never throws. */
function safeCurrency(currency: string | null | undefined): string {
  if (!currency) return 'USD'
  return isCurrencyCode(currency) ? currency : 'USD'
}

export function isCurrencyCode(currency: string): boolean {
  if (!/^[A-Z]{3}$/.test(currency)) return false
  if (supportedCurrencyCodes && !supportedCurrencyCodes.has(currency)) return false

  try {
    new Intl.NumberFormat('en-US', { style: 'currency', currency })
    return true
  } catch {
    return false
  }
}

export function formatAmount(amount: number, currency: string | null | undefined): string {
  const value = Number.isFinite(Number(amount)) ? Number(amount) : 0
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: safeCurrency(currency),
    minimumFractionDigits: 2,
  }).format(value)
}

/**
 * The bare number with no currency symbol, for use inside a currency <select> or a
 * label that already names the currency.
 */
export function formatAmountPlain(amount: number): string {
  const value = Number.isFinite(Number(amount)) ? Number(amount) : 0
  return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2 }).format(value)
}