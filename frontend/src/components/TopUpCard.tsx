import { useState } from 'react'
import { ApiError, topUpWallet } from '../lib/api'
import { formatAmount, isCurrencyCode } from '../lib/money'
import { describeError } from '../lib/session'
import type { WalletResponse } from '../lib/types'

interface TopUpCardProps {
  token: string
  wallet: WalletResponse | null
  /** Kept in step with the balance everywhere it is displayed. */
  onWalletChange: (wallet: WalletResponse) => void
}

/**
 * Adds money to the caller's own wallet, which is the only way a balance can grow.
 *
 * Shared by the overview and the payment page because both need it and the currency
 * rule makes it easy to get wrong twice: a wallet's currency is fixed when it is
 * opened, so this sends the wallet's own currency, and only sends one at all when the
 * wallet has not been opened yet — in which case the caller's chosen currency is what
 * fixes it.
 */
export function TopUpCard({
  token,
  wallet,
  onWalletChange,
}: TopUpCardProps) {
  const [amount, setAmount] = useState('')
  const [newWalletCurrency, setNewWalletCurrency] = useState('USD')
  const [status, setStatus] = useState<'idle' | 'saving' | 'error'>('idle')
  const [error, setError] = useState('')
  const selectedCurrency = wallet?.currency ?? newWalletCurrency

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const parsed = Number(amount)

    if (!wallet?.currency && !isCurrencyCode(newWalletCurrency)) {
      setStatus('error')
      setError('Enter a valid three-letter ISO currency code, such as USD or EUR.')
      return
    }

    if (!amount.trim() || !Number.isFinite(parsed) || parsed < 0.01) {
      setStatus('error')
      setError('Enter an amount of at least 0.01.')
      return
    }

    setStatus('saving')
    setError('')
    try {
      const updated = await topUpWallet(token, {
        amount: parsed,
        currency: selectedCurrency,
      })
      onWalletChange(updated)
      setAmount('')
      setStatus('idle')
    } catch (caught) {
      setStatus('error')
      setError(caught instanceof ApiError ? caught.message : describeError(caught))
    }
  }

  const balance = wallet ? Number(wallet.balance) : 0
  const isOpen = wallet !== null && wallet.createdAt !== null

  return (
    <div className="topup-block">
      <dl className="summary-list">
        <div><dt>Balance</dt><dd>{formatAmount(balance, selectedCurrency)}</dd></div>
        <div><dt>Currency</dt><dd>{wallet?.currency ?? selectedCurrency}</dd></div>
      </dl>
      {wallet?.currency && (
        <p className="summary-note">
          This wallet is fixed to {wallet.currency}; top-ups and payments use this currency.
        </p>
      )}

      <form className="topup-form" onSubmit={submit} noValidate>
        {!wallet?.currency && (
          <label className="field-label">
            Currency code
            <input
              className="currency-code-input"
              type="text"
              inputMode="text"
              autoCapitalize="characters"
              autoComplete="off"
              spellCheck={false}
              maxLength={3}
              value={newWalletCurrency}
              onChange={(event) => {
                setNewWalletCurrency(event.target.value.replace(/[^a-z]/gi, '').toUpperCase())
                setError('')
                setStatus('idle')
              }}
              placeholder="USD"
              aria-describedby="topup-currency-hint"
              aria-invalid={status === 'error' && !isCurrencyCode(newWalletCurrency)}
            />
            <small id="topup-currency-hint" className="field-hint">
              Enter any three-letter ISO code. This sets your wallet currency permanently.
            </small>
          </label>
        )}
        <label className="field-label">
          Add money
          <input
            type="number"
            inputMode="decimal"
            step="0.01"
            min="0.01"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            placeholder="0.00"
          />
        </label>
        <button type="submit" className="btn-action" disabled={status === 'saving'}>
          {status === 'saving' ? 'Adding…' : 'Top up'}
        </button>
      </form>

      {error && <p className="form-error">{error}</p>}

      {!isOpen && wallet && (
        <p className="summary-note">
          <span aria-hidden="true">◈</span> Your wallet opens the first time you add money
          or send a payment.
        </p>
      )}
    </div>
  )
}