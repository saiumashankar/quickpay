import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { displayHandle, normalizeHandle, toRecipientHandle } from './handles.ts'

/**
 * These mirror HandleService.VALID in auth-service: ^[a-z0-9][a-z0-9._]{2,29}$ after
 * a leading "@" is dropped and the rest lowercased. The tests below are written
 * against that behaviour deliberately: if the backend rule changes, this file is
 * where the frontend should be told it no longer matches.
 */
describe('normalizeHandle', () => {
  it('lower-cases, which is what makes two spellings one identity', () => {
    assert.equal(normalizeHandle('Sai123'), 'sai123')
    assert.equal(normalizeHandle('SAI123'), 'sai123')
  })

  it('drops a single leading @ but not a handle that legitimately starts with one', () => {
    assert.equal(normalizeHandle('@sai123'), 'sai123')
    assert.equal(normalizeHandle('@@sai123'), null)
  })

  it('trims surrounding whitespace', () => {
    assert.equal(normalizeHandle('  sai123  '), 'sai123')
  })

  it('accepts dot and underscore, which the rules explicitly allow', () => {
    assert.equal(normalizeHandle('sai.123_x'), 'sai.123_x')
  })

  it('accepts a digit as the first character', () => {
    assert.equal(normalizeHandle('1sai'), '1sai')
  })

  it('rejects anything shorter than three characters', () => {
    assert.equal(normalizeHandle('ab'), null)
    assert.equal(normalizeHandle('@ab'), null)
    assert.equal(normalizeHandle(''), null)
    assert.equal(normalizeHandle('   '), null)
  })

  it('accepts exactly thirty characters and rejects thirty-one', () => {
    const thirty = 'a'.repeat(30)
    const thirtyOne = 'a'.repeat(31)
    assert.equal(normalizeHandle(thirty), thirty)
    assert.equal(normalizeHandle(thirtyOne), null)
  })

  it('rejects characters that are not letters, digits, dot or underscore', () => {
    // An email address is not a handle: this is the mistake the old form invited.
    assert.equal(normalizeHandle('sai@example.com'), null)
    assert.equal(normalizeHandle('sai 123'), null)
    assert.equal(normalizeHandle('sai-123'), null)
    assert.equal(normalizeHandle('sai/123'), null)
  })

  it('rejects a handle that does not start with a letter or digit', () => {
    assert.equal(normalizeHandle('.sai'), null)
    assert.equal(normalizeHandle('_sai'), null)
  })
})

describe('toRecipientHandle', () => {
  it('sends the canonical handle the backend will resolve', () => {
    assert.equal(toRecipientHandle('@Sai123'), 'sai123')
  })

  it('passes through input it cannot canonicalise rather than sending an empty string', () => {
    // Payment-service validates the handle anyway. Sending "" would turn a clear
    // validation error into a confusing one about a missing value.
    assert.equal(toRecipientHandle('@bad email'), '@bad email')
  })
})

describe('displayHandle', () => {
  it('prefixes the canonical handle for display', () => {
    assert.equal(displayHandle('sai123'), '@sai123')
  })

  it('renders an absent handle as a dash rather than a bare @', () => {
    // An unopened wallet reports handle: null.
    assert.equal(displayHandle(''), '—')
  })
})