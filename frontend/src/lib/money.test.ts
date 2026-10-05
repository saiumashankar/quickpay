import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { formatAmount, formatAmountPlain, isCurrencyCode } from './money.ts'

describe('isCurrencyCode', () => {
  it('accepts valid three-letter codes beyond the common currency list', () => {
    assert.equal(isCurrencyCode('INR'), true)
    assert.equal(isCurrencyCode('THB'), true)
  })

  it('rejects codes that are not exactly three uppercase letters', () => {
    assert.equal(isCurrencyCode('US'), false)
    assert.equal(isCurrencyCode('USDD'), false)
    assert.equal(isCurrencyCode('uSd'), false)
    assert.equal(isCurrencyCode('1SD'), false)
    assert.equal(isCurrencyCode('ZZZ'), false)
  })
})

describe('formatAmount', () => {
  it('formats a normal amount in its currency', () => {
    assert.equal(formatAmount(79, 'USD'), '$79.00')
  })

  it('survives the null currency of a wallet that has never been opened', () => {
    // WalletService.opening() returns currency: null. Intl.NumberFormat throws a
    // RangeError on null rather than falling back, so this is the case that would
    // otherwise crash the payment page for every brand new user.
    assert.equal(formatAmount(0, null), '$0.00')
  })

  it('falls back for an unrecognised currency instead of throwing', () => {
    assert.doesNotThrow(() => formatAmount(10, 'NOT-A-CURRENCY'))
  })

  it('treats a non-numeric balance as zero rather than printing NaN', () => {
    assert.equal(formatAmount(Number.NaN, 'USD'), '$0.00')
  })

  it('keeps two decimal places for a value that arrives with four', () => {
    // BigDecimal is serialised with scale 4, so "50.0000" arrives as 50.
    assert.equal(formatAmount(50.0000, 'USD'), '$50.00')
  })
})

describe('formatAmountPlain', () => {
  it('formats without a currency symbol', () => {
    assert.equal(formatAmountPlain(1234.5), '1,234.50')
  })
})