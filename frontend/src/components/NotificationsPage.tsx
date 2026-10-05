import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ApiError,
  getNotificationPreferences,
  listMailpitMessages,
  listPayments,
  sendTestNotification,
  updateNotificationPreferences,
} from '../lib/api'
import { displayHandle } from '../lib/handles'
import { formatAmount } from '../lib/money'
import { describeError } from '../lib/session'
import type {
  MailpitMessage,
  NotificationPreferences,
  PaymentEventType,
  PaymentResponse,
  UserResponse,
  WalletResponse,
} from '../lib/types'
import { StatusBadge } from './StatusBadge'
import { ProfileMenu } from './ProfileMenu'

interface NotificationsPageProps {
  token: string
  user: UserResponse
  payment: PaymentResponse | null
  wallet: WalletResponse | null
  onPayAgain: () => void
  onBack: () => void
  onTransactions: () => void
  onSignOut: () => void
}

/**
 * notification-service mails both parties of a transfer, and Mailpit holds every
 * message the stack has ever sent rather than a per-user mailbox. Filtering to the
 * address the signed-in user actually receives mail at is what stops another
 * person's receipts appearing in this inbox.
 */
function isAddressedTo(message: MailpitMessage, address: string): boolean {
  const wanted = address.trim().toLowerCase()
  return (message.To ?? []).some((to) => (to?.Address ?? '').toLowerCase() === wanted)
}

/**
 * The three subjects NotificationService.subjectFor can produce, and which side of
 * the transfer each describes. There is no endpoint exposing the party, so the
 * wording is the only signal available.
 */
function directionOf(subject: string): string {
  if (subject.startsWith('You received')) return 'Received'
  if (subject.startsWith('Payment failed')) return 'Failed'
  return 'Sent'
}

/**
 * Stage 4, reached only after a payment succeeds.
 *
 * The backend has no "list notifications" endpoint: notification-service consumes
 * Kafka events and sends email, keeping no readable store. So this page shows the
 * two things that genuinely exist — the payment record from payment-service, and the
 * delivered emails from Mailpit.
 */
export function NotificationsPage({
  token,
  user,
  payment,
  wallet,
  onPayAgain,
  onBack,
  onTransactions,
  onSignOut,
}: NotificationsPageProps) {
  const [messages, setMessages] = useState<MailpitMessage[]>([])
  const [mailError, setMailError] = useState('')
  const [mailLoading, setMailLoading] = useState(true)

  const [payments, setPayments] = useState<PaymentResponse[]>([])
  const [paymentsError, setPaymentsError] = useState('')

  const [preferences, setPreferences] = useState<NotificationPreferences | null>(null)
  const [emailEnabled, setEmailEnabled] = useState(true)
  const [prefsStatus, setPrefsStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')

  const [testType, setTestType] = useState<PaymentEventType>('PAYMENT_SUCCESS')
  const [testStatus, setTestStatus] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle')

  const loadMail = useCallback(async () => {
    setMailLoading(true)
    try {
      const listing = await listMailpitMessages()
      setMessages(listing.messages ?? [])
      setMailError('')
    } catch (caught) {
      setMailError(describeError(caught))
    } finally {
      setMailLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadMail()
  }, [loadMail])

  useEffect(() => {
    let active = true

    listPayments(token)
      .then((result) => {
        if (active) {
          setPayments(result)
          setPaymentsError('')
        }
      })
      .catch((caught: unknown) => {
        if (active) setPaymentsError(describeError(caught))
      })

    getNotificationPreferences(token)
      .then((prefs) => {
        if (active) {
          setPreferences(prefs)
          setEmailEnabled(prefs.emailEnabled)
        }
      })
      .catch(() => {
        // Preference is a convenience here; a failure should not break the page.
        if (active) setEmailEnabled(true)
      })

    return () => {
      active = false
    }
  }, [token])

  // The Kafka consumer is asynchronous, so a freshly sent receipt may not have arrived
  // by the time this page mounts. A short retry covers that gap.
  useEffect(() => {
    if (messages.length > 0) return
    const timer = setTimeout(() => void loadMail(), 2500)
    return () => clearTimeout(timer)
  }, [messages.length, loadMail])

  const toggleEmail = async (enabled: boolean) => {
    setEmailEnabled(enabled)
    setPrefsStatus('saving')
    try {
      const prefs = await updateNotificationPreferences(token, enabled)
      setPreferences(prefs)
      setEmailEnabled(prefs.emailEnabled)
      setPrefsStatus('saved')
    } catch (caught) {
      setPrefsStatus('error')
      setMailError(caught instanceof ApiError ? caught.message : describeError(caught))
    }
  }

  const sendTest = async () => {
    setTestStatus('sending')
    try {
      await sendTestNotification(token, testType, user, payment?.amount ?? 1)
      // 200 here only means the event was accepted. Mail still has to travel through
      // the consumer and the provider, so the inbox is re-read before claiming success.
      setTestStatus('sent')
      await loadMail()
    } catch (caught) {
      setTestStatus('error')
      setMailError(describeError(caught))
    }
  }

  const deliveredTo = preferences?.email ?? user.email

  /**
   * Only the mail addressed to this user. MailHog is a shared sink holding every
   * message the stack has sent, and notification-service mails both parties of each
   * transfer, so an unfiltered list would put another account's receipts on screen.
   *
   * Mail is sent to preferences.email, which is the address auth-service currently
   * has on file, so that is the one to match on rather than the email the user
   * happened to sign in with.
   */
  const myMessages = useMemo(
    () => messages.filter((message) => isAddressedTo(message, deliveredTo)),
    [messages, deliveredTo],
  )

  return (
    <div className="dashboard">
      <main className="main-content">
        <header className="top-bar">
          <div className="breadcrumb">
            <button type="button" className="text-link" onClick={onBack}>Overview</button>
            <span>/</span>
            <strong>Notifications</strong>
          </div>
          <div className="top-bar-actions">
            <span className="topbar-divider" />
            <ProfileMenu user={user} onTransactions={onTransactions} onSignOut={onSignOut} />
          </div>
        </header>

        <div className="page-body">
          <div className="subpage">
            <div className="subpage-intro">
              <span className="eyebrow">{payment ? 'PAYMENT CONFIRMATION' : 'ACCOUNT SETTINGS'}</span>
              <h2>{payment ? 'Payment successful' : 'Notifications'}</h2>
              <p>
                {payment
                  ? <>Your payment went through and a confirmation is on its way to {deliveredTo}.{' '}
                    <button type="button" className="text-link" onClick={onPayAgain}>
                      Make another payment <span aria-hidden="true">↩</span>
                    </button></>
                  : <>Manage email receipts and review delivered messages for {user.email}.</>}
              </p>
            </div>

            {payment && <section className="panel-card receipt-card">
              <div className="receipt-head">
                <span className="receipt-check" aria-hidden="true">✓</span>
                <div>
                  <strong>{formatAmount(payment.amount, payment.currency)}</strong>
                  <span>to {displayHandle(payment.recipientHandle)}</span>
                </div>
                <StatusBadge status={payment.status} />
              </div>
              <dl className="receipt-grid">
                <div><dt>Reference</dt><dd>{payment.id}</dd></div>
                <div><dt>From</dt><dd>{displayHandle(payment.senderHandle)}</dd></div>
                <div><dt>Created</dt><dd>{new Date(payment.createdAt).toLocaleString()}</dd></div>
                <div><dt>Description</dt><dd>{payment.description || '—'}</dd></div>
                {wallet && (
                  <div><dt>Balance</dt><dd>{formatAmount(Number(wallet.balance), wallet.currency)}</dd></div>
                )}
              </dl>
              <div className="receipt-actions">
                <button type="button" className="btn-primary" onClick={onPayAgain}>Make another payment</button>
              </div>
            </section>}

            <section className="notify-grid">
              <article className="panel-card notify-card">
                <div className="panel-heading">
                  <div><h3>Inbox</h3><p>Receipts delivered to your email</p></div>
                  <button type="button" className="btn-action" onClick={() => void loadMail()}>
                    {mailLoading ? 'Loading…' : 'Refresh'}
                  </button>
                </div>

                {mailError && <p className="form-error">{mailError}</p>}

                {!mailError && myMessages.length === 0 && (
                  <p className="empty-state">
                    {mailLoading
                      ? 'Checking your inbox…'
                      : emailEnabled
                        ? 'No notifications delivered yet. Your receipt is being processed.'
                        : 'No notifications delivered yet, and email notifications are off.'}
                  </p>
                )}

                {myMessages.length > 0 && (
                  <ul className="notify-list">
                    {myMessages.slice(0, 8).map((message) => (
                      <li key={message.ID}>
                        <span className="notify-dot" aria-hidden="true" />
                        <span className="notify-main">
                          <strong>{message.Subject}</strong>
                          <small>
                            {directionOf(message.Subject)} ·{' '}
                            {new Date(message.Created).toLocaleString()}
                          </small>
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </article>

              <article className="panel-card notify-card">
                <div className="panel-heading">
                  <div><h3>Preferences</h3><p>How QuickPay reaches you</p></div>
                </div>

                <div className="preference-row">
                  <span>
                    <strong>Email notifications</strong>
                    <small>Send a receipt for every payment</small>
                  </span>
                  <label className="switch">
                    <input
                      type="checkbox"
                      checked={emailEnabled}
                      onChange={(event) => void toggleEmail(event.target.checked)}
                    />
                    <span className="switch-track" aria-hidden="true" />
                    <span className="switch-text">{emailEnabled ? 'On' : 'Off'}</span>
                  </label>
                </div>

                {!emailEnabled && (
                  <p className="notify-hint">
                    Email notifications are off, so payment receipts will not be sent.
                  </p>
                )}

                {prefsStatus === 'saved' && <p className="notify-success">Preference saved.</p>}
                {prefsStatus === 'error' && <p className="form-error">Could not save your preference.</p>}

                <div className="notify-divider" />

                <p className="notify-hint">
                  Send a test event to <strong>{deliveredTo}</strong> to check the notification
                  pipeline. It is a test, not a payment.
                </p>

                <div className="form-row">
                  <label className="field-label">
                    Test event
                    <select
                      value={testType}
                      onChange={(event) => setTestType(event.target.value as PaymentEventType)}
                    >
                      <option value="PAYMENT_SUCCESS">Payment succeeded</option>
                      <option value="PAYMENT_FAILED">Payment failed</option>
                    </select>
                  </label>
                </div>

                <button
                  type="button"
                  className="btn-action"
                  onClick={() => void sendTest()}
                  disabled={testStatus === 'sending'}
                >
                  {testStatus === 'sending' ? 'Sending…' : 'Send a test notification'}
                </button>
                {testStatus === 'sent' && (
                  <p className="notify-success">Accepted by the notification service.</p>
                )}
                {testStatus === 'error' && (
                  <p className="form-error">Could not send the test notification.</p>
                )}
              </article>
            </section>

            <section className="panel-card transactions-section">
              <div className="panel-heading">
                <div><h3>Payment history</h3><p>Straight from payment-service</p></div>
              </div>
              {paymentsError && <p className="form-error">{paymentsError}</p>}
              {!paymentsError && payments.length === 0 && (
                <p className="empty-state">No payments found.</p>
              )}
              {payments.length > 0 && (
                <div className="table-scroll">
                  <table className="transaction-table">
                    <thead>
                      <tr><th>Recipient</th><th>Reference</th><th>Date</th><th>Amount</th><th>Status</th></tr>
                    </thead>
                    <tbody>
                      {payments.map((item) => (
                        <tr key={item.id}>
                          <td>
                            <div className="recipient-cell">
                              <span className="recipient-avatar">{initials(item.recipientHandle)}</span>
                              <span>
                                <strong>{displayHandle(item.recipientHandle)}</strong>
                                <small>{item.description || 'Payment'}</small>
                              </span>
                            </div>
                          </td>
                          <td className="transaction-id">{item.id}</td>
                          <td className="transaction-date">{new Date(item.createdAt).toLocaleString()}</td>
                          <td className={`transaction-amount ${item.status === 'SUCCESS' ? 'received' : ''}`}>
                            {formatAmount(item.amount, item.currency)}
                          </td>
                          <td><StatusBadge status={item.status} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        </div>
      </main>
    </div>
  )
}

function initials(value: string): string {
  const cleaned = value.replace(/[^a-zA-Z]/g, '')
  return cleaned.slice(0, 2).toUpperCase() || 'QP'
}