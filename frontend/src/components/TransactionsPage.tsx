import { useEffect, useMemo, useState } from 'react'
import { listPayments } from '../lib/api'
import { displayHandle } from '../lib/handles'
import { formatAmount } from '../lib/money'
import { describeError } from '../lib/session'
import type { PaymentResponse, UserResponse } from '../lib/types'
import { ProfileMenu } from './ProfileMenu'
import { StatusBadge } from './StatusBadge'

interface TransactionsPageProps {
  token: string
  user: UserResponse
  onBack: () => void
  onTransactions: () => void
  onSignOut: () => void
}

export function TransactionsPage({
  token,
  user,
  onBack,
  onTransactions,
  onSignOut,
}: TransactionsPageProps) {
  const [payments, setPayments] = useState<PaymentResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true

    listPayments(token)
      .then((result) => {
        if (active) {
          setPayments(result)
          setError('')
        }
      })
      .catch((caught: unknown) => {
        if (active) setError(describeError(caught))
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })

    return () => {
      active = false
    }
  }, [token])

  const totalsByCurrency = useMemo(() => {
    const totals = new Map<string, number>()
    for (const payment of payments) {
      if (payment.status === 'SUCCESS') {
        totals.set(payment.currency, (totals.get(payment.currency) ?? 0) + Number(payment.amount))
      }
    }
    return [...totals.entries()]
  }, [payments])

  const completedCount = payments.filter((payment) => payment.status === 'SUCCESS').length
  const failedCount = payments.filter((payment) => payment.status === 'FAILED').length
  const pendingCount = payments.filter((payment) => payment.status === 'PENDING').length

  return (
    <div className="dashboard">
      <main className="main-content">
        <header className="top-bar">
          <div className="breadcrumb">
            <button type="button" className="text-link" onClick={onBack}>Overview</button>
            <span>/</span>
            <strong>Transactions</strong>
          </div>
          <div className="top-bar-actions">
            <span className="topbar-divider" />
            <ProfileMenu user={user} onTransactions={onTransactions} onSignOut={onSignOut} />
          </div>
        </header>

        <div className="page-body">
          <div className="subpage transactions-page">
            <div className="subpage-intro">
              <span className="eyebrow">ACCOUNT ACTIVITY</span>
              <h2>Your transactions</h2>
              <p>All payment attempts for {displayHandle(user.username)}, with their latest status.</p>
            </div>

            <div className="transaction-summary-grid">
              <article className="transaction-summary-card">
                <span>Total transactions</span>
                <strong>{isLoading ? '—' : payments.length}</strong>
                <small>Payment records on your account</small>
              </article>
              <article className="transaction-summary-card">
                <span>Completed</span>
                <strong>{isLoading ? '—' : completedCount}</strong>
                <small>
                  {isLoading || totalsByCurrency.length === 0
                    ? 'Successful payments'
                    : totalsByCurrency.map(([currency, amount]) => formatAmount(amount, currency)).join(' · ')}
                </small>
              </article>
              <article className="transaction-summary-card">
                <span>Pending</span>
                <strong>{isLoading ? '—' : pendingCount}</strong>
                <small>Awaiting a final status</small>
              </article>
              <article className="transaction-summary-card">
                <span>Unsuccessful</span>
                <strong>{isLoading ? '—' : failedCount}</strong>
                <small>Did not complete</small>
              </article>
            </div>

            <section className="panel-card transactions-section">
              <div className="panel-heading">
                <div>
                  <h3>Payment history</h3>
                  <p>Newest activity first</p>
                </div>
                <span className="transaction-count">
                  {isLoading ? 'Loading' : `${payments.length} ${payments.length === 1 ? 'record' : 'records'}`}
                </span>
              </div>

              {error && <p className="form-error" role="alert">{error}</p>}
              {!error && isLoading && <p className="empty-state">Loading your transactions…</p>}
              {!error && !isLoading && payments.length === 0 && (
                <p className="empty-state">No transactions yet. Your payments will appear here.</p>
              )}
              {!error && payments.length > 0 && (
                <div className="table-scroll">
                  <table className="transaction-table">
                    <thead>
                      <tr>
                        <th>Activity</th>
                        <th>Reference</th>
                        <th>Date</th>
                        <th>Amount</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {payments.map((payment) => (
                        <tr key={payment.id}>
                          <td>
                            <div className="recipient-cell">
                              <span className="recipient-avatar" aria-hidden="true">
                                {(payment.direction === 'RECEIVED' ? payment.senderHandle : payment.recipientHandle)
                                  .replace(/[^a-z]/gi, '').slice(0, 2).toUpperCase() || 'QP'}
                              </span>
                              <span>
                                <strong>
                                  {payment.direction === 'RECEIVED'
                                    ? `Received from ${displayHandle(payment.senderHandle)}`
                                    : `Sent to ${displayHandle(payment.recipientHandle)}`}
                                </strong>
                                <small>{payment.description || 'Payment'}</small>
                              </span>
                            </div>
                          </td>
                          <td className="transaction-id">{payment.id}</td>
                          <td className="transaction-date">{new Date(payment.createdAt).toLocaleString()}</td>
                          <td className={`transaction-amount ${payment.direction === 'RECEIVED' && payment.status === 'SUCCESS' ? 'received' : ''}`}>
                            {payment.direction === 'RECEIVED' ? '+' : '−'}{formatAmount(payment.amount, payment.currency)}
                          </td>
                          <td><StatusBadge status={payment.status} /></td>
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
