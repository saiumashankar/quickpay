import { useCallback, useEffect, useState } from 'react'
import { ApiError, createPayment, getWallet, listPayments } from '../lib/api'
import { HANDLE_RULE, displayHandle, normalizeHandle, toRecipientHandle } from '../lib/handles'
import { formatAmount } from '../lib/money'
import { describeError } from '../lib/session'
import type { PaymentResponse, UserResponse, WalletResponse } from '../lib/types'
import { ProfileMenu } from './ProfileMenu'
import { StatusBadge } from './StatusBadge'
import { TopUpCard } from './TopUpCard'

interface PaymentPageProps {
  token: string
  user: UserResponse
  wallet: WalletResponse | null
  onWalletChange: (wallet: WalletResponse) => void
  onPaid: (payment: PaymentResponse, wallet: WalletResponse | null) => void
  /** Back to the overview, without signing out. */
  onBack: () => void
  onTransactions: () => void
  onSignOut: () => void
}

/**
 * Stage 3 of the flow. Creates a payment via POST /payments on payment-service (8082),
 * which requires both the bearer token and an `Idempotency-Key` header.
 *
 * The recipient is a handle, which is the whole idea: the user types a person, not an
 * account number. The handle is canonicalised here exactly as HandleService does, so a
 * typo is caught before the request rather than as an opaque 400.
 *
 * A wallet's currency is chosen once and never changes â€” payment-service refuses to
 * convert between currencies. So the currency is picked in one place on this page and
 * then locked once the wallet has been opened, rather than being free per payment.
 */
export function PaymentPage({
  token,
  user,
  wallet,
  onWalletChange,
  onPaid,
  onBack,
  onTransactions,
  onSignOut,
}: PaymentPageProps) {
  const [amount, setAmount] = useState('')
  const [recipient, setRecipient] = useState('')
  const [description, setDescription] = useState('')

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const [history, setHistory] = useState<PaymentResponse[]>([])
  const [historyError, setHistoryError] = useState('')

  const [balance, setBalance] = useState<WalletResponse | null>(wallet)
  const [walletLoading, setWalletLoading] = useState(wallet === null)
  const [walletError, setWalletError] = useState('')

  const syncWallet = useCallback((updated: WalletResponse) => {
    setBalance(updated)
    onWalletChange(updated)
  }, [onWalletChange])

  /**
   * The wallet's currency, once it has one. Null means the wallet has never been
   * opened, so the currency is still the user's to choose.
   */
  const walletCurrency = balance?.currency ?? null
  const currencyLocked = walletCurrency !== null
  const currency = walletCurrency ?? 'USD'

  const loadWallet = useCallback(async () => {
    setWalletLoading(true)
    try {
      const loaded = await getWallet(token)
      syncWallet(loaded)
      setWalletError('')
    } catch (caught) {
      setWalletError(describeError(caught))
    } finally {
      setWalletLoading(false)
    }
  }, [token, syncWallet])

  useEffect(() => {
    let active = true
    listPayments(token)
      .then((payments) => {
        if (active) setHistory(payments)
      })
      .catch((caught: unknown) => {
        if (active) setHistoryError(describeError(caught))
      })
    return () => {
      active = false
    }
  }, [token])

  useEffect(() => {
    void loadWallet()
  }, [loadWallet])

  const validate = (): boolean => {
    const errors: Record<string, string> = {}
    const parsed = Number(amount)

    if (!amount.trim()) {
      errors.amount = 'Enter an amount.'
    } else if (!Number.isFinite(parsed) || parsed <= 0) {
      errors.amount = 'Amount must be greater than zero.'
    } else if (parsed < 0.01) {
      errors.amount = 'Amount must be at least 0.01.'
    } else if (!/^\d+(\.\d{1,4})?$/.test(amount.trim())) {
      errors.amount = 'Amount allows at most 4 decimal places.'
    }

    const target = normalizeHandle(recipient)
    if (!recipient.trim()) {
      errors.recipient = 'Enter a recipient handle.'
    } else if (!target) {
      errors.recipient = HANDLE_RULE
    } else if (target === user.username) {
      // payment-service rejects this outright; catching it here avoids a pointless
      // round trip and an error message the user cannot act on.
      errors.recipient = 'You cannot send money to your own handle.'
    }

    if (description.length > 500) {
      errors.description = 'Description must be 500 characters or fewer.'
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError('')
    if (!validate()) return

    setIsSubmitting(true)
    try {
      const payment = await createPayment(
        token,
        {
          amount: Number(amount),
          currency,
          recipientHandle: toRecipientHandle(recipient),
          ...(description.trim() ? { description: description.trim() } : {}),
        },
        // Required header. Regenerated per submission so retries are treated as new
        // payments, while a single submit that is retried at the network level is not.
        crypto.randomUUID(),
      )

      // Read the wallet back rather than subtracting the amount locally: the balance
      // is owned by payment-service, and a self-computed figure would drift from it.
      let latestWallet: WalletResponse | null = null
      try {
        latestWallet = await getWallet(token)
        setBalance(latestWallet)
        onWalletChange(latestWallet)
      } catch {
        // The payment itself was recorded, so a failed balance read must not block the
        // stage change; the next mount reloads it.
      }

      if (payment.status !== 'SUCCESS') {
        setError(
          payment.status === 'FAILED'
            ? 'Your payment was declined â€” your balance was too low. Top up and try again.'
            : `Your payment is ${payment.status.toLowerCase()}. It has not completed yet.`,
        )
        return
      }

      setHistory((current) => [payment, ...current])
      onPaid(payment, latestWallet)
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(caught.message)
        setFieldErrors(caught.fields)
      } else {
        setError(describeError(caught))
      }
    } finally {
      setIsSubmitting(false)
    }
  }


  const parsedAmount = Number(amount)
  const canPreview = Number.isFinite(parsedAmount) && parsedAmount > 0
  const recipientPreview = normalizeHandle(recipient)
  const balanceValue = balance ? Number(balance.balance) : null
  const hasEnough = balanceValue !== null && canPreview && parsedAmount <= balanceValue

  return (
    <div className="dashboard">
      <main className="main-content">
        <header className="top-bar">
          <div className="breadcrumb">
            <button type="button" className="text-link" onClick={onBack}>Overview</button>
            <span>/</span>
            <strong>Make a payment</strong>
          </div>
          <div className="top-bar-actions">
            <span className="topbar-divider" />
            <ProfileMenu user={user} onTransactions={onTransactions} onSignOut={onSignOut} />
          </div>
        </header>

        <div className="page-body">
          <div className="subpage">
<div className="subpage-intro">
              <span className="eyebrow">STEP 4 OF 5 · PAYMENT</span>
              <h2>Make a payment</h2>
              <p>Signed in as {user.email}.</p>
            </div>

            <div className="payment-layout">
              <form className="payment-form" onSubmit={submit} noValidate>
                <h3>Payment details</h3>

                <div className="form-row">
                  <label className="field-label">
                    Amount
                    <input
                      type="number"
                      inputMode="decimal"
                      step="0.01"
                      min="0.01"
                      value={amount}
                      onChange={(event) => setAmount(event.target.value)}
                      placeholder="0.00"
                      required
                    />
                  </label>
                  <div className="field-label">
                    Currency
                    <div className="payment-currency-value" aria-label={`Payment currency ${currency}`}>
                      <span>{currency}</span>
                      <small>{currencyLocked ? 'Wallet currency' : 'Default wallet currency'}</small>
                    </div>
                  </div>
                </div>
                {fieldErrors.amount && <p className="form-error">{fieldErrors.amount}</p>}
                {currencyLocked && (
                  <p className="field-hint field-hint-wide">
                    Your wallet is in {walletCurrency}; payments must use this currency.
                  </p>
                )}
                {!currencyLocked && (
                  <p className="field-hint field-hint-wide">
                    Payments use USD until your first top-up sets your wallet currency.
                  </p>
                )}

                <label className="field-label">
                  Recipient handle
                  <input
                    value={recipient}
                    onChange={(event) => setRecipient(event.target.value)}
                    placeholder="sai123"
                    maxLength={31}
                    required
                  />
                  <small className="field-hint">The handle of the person you are paying.</small>
                </label>
                {fieldErrors.recipient && <p className="form-error">{fieldErrors.recipient}</p>}

                <label className="field-label">
                  Description <small className="field-hint">optional</small>
                  <input
                    value={description}
                    onChange={(event) => setDescription(event.target.value)}
                    placeholder="What is this payment for?"
                    maxLength={500}
                  />
                </label>
                {fieldErrors.description && <p className="form-error">{fieldErrors.description}</p>}

                {error && <p className="form-error" role="alert">{error}</p>}

                <div className="actions">
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isSubmitting || walletLoading || balance === null}
                  >
                    {walletLoading
                      ? 'Loading wallet…'
                      : isSubmitting
                        ? 'Processing…'
                      : canPreview
                        ? `Pay ${formatAmount(parsedAmount, currency)}`
                        : 'Pay now'}
                    <span aria-hidden="true">â†—</span>
                  </button>
                </div>
              </form>

              <aside className="payment-side">
<section className="panel-card summary-card">
                  <div className="panel-heading">
                    <div>
                      <h3>Your wallet</h3>
                      <p>{balance?.handle ? displayHandle(balance.handle) : 'Not opened yet'}</p>
                    </div>
                    <button type="button" className="btn-action" onClick={() => void loadWallet()}>
                      Refresh
                    </button>
                  </div>

                  {walletError && <p className="form-error">{walletError}</p>}

                  <TopUpCard
                    token={token}
                    wallet={balance}
                    onWalletChange={syncWallet}
                  />
                </section>

                <section className="panel-card summary-card">
                  <div className="panel-heading"><div><h3>Summary</h3><p>Review before you pay</p></div></div>
                  <dl className="summary-list">
                    <div><dt>Recipient</dt><dd>{recipientPreview ? displayHandle(recipientPreview) : 'â€”'}</dd></div>
                    <div><dt>Amount</dt><dd>{canPreview ? formatAmount(parsedAmount, currency) : 'â€”'}</dd></div>
                    <div><dt>Currency</dt><dd>{currency}</dd></div>
                    <div><dt>Fee</dt><dd>Free</dd></div>
                  </dl>
                  <div className="summary-total">
                    <span>Total</span>
                    <strong>{canPreview ? formatAmount(parsedAmount, currency) : 'â€”'}</strong>
                  </div>
                  {canPreview && balanceValue !== null && !hasEnough && (
                    <p className="form-error">
                      Your balance is {formatAmount(balanceValue, walletCurrency ?? currency)}.
                      Top up before sending.
                    </p>
                  )}
                  <p className="summary-note"><span aria-hidden="true">â—ˆ</span> Secured by QuickPay. Settles instantly on success.</p>
                </section>

                <section className="panel-card history-card">
                  <div className="panel-heading"><div><h3>Your payments</h3><p>Most recent first</p></div></div>
                  {historyError && <p className="form-error">{historyError}</p>}
                  {!historyError && history.length === 0 && (
                    <p className="empty-state">No payments yet. This will be your first.</p>
                  )}
                  {history.length > 0 && (
                    <ul className="history-list">
                      {history.slice(0, 5).map((payment) => (
                        <li key={payment.id}>
                          <span className="history-main">
                            <strong>{displayHandle(payment.recipientHandle)}</strong>
                            <small>{new Date(payment.createdAt).toLocaleString()}</small>
                          </span>
                          <span className="history-side">
                            <StatusBadge status={payment.status} />
                            <strong>{formatAmount(payment.amount, payment.currency)}</strong>
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              </aside>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
