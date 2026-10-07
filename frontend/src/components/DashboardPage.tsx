import { useCallback, useEffect, useState } from 'react'
import { getWallet, listPayments } from '../lib/api'
import { displayHandle } from '../lib/handles'
import { formatAmount } from '../lib/money'
import { describeError } from '../lib/session'
import type { PaymentResponse, UserResponse, WalletResponse } from '../lib/types'
import { ProfileMenu } from './ProfileMenu'
import { StatusBadge } from './StatusBadge'
import { TopUpCard } from './TopUpCard'

interface DashboardPageProps {
  token: string
  user: UserResponse
  wallet: WalletResponse | null
  onWalletChange: (wallet: WalletResponse) => void
  onPay: () => void
  onNotifications: () => void
  onTransactions: () => void
  onSignOut: () => void
}

export function DashboardPage({
  token,
  user,
  wallet,
  onWalletChange,
  onPay,
  onNotifications,
  onTransactions,
  onSignOut,
}: DashboardPageProps) {
  const [balance, setBalance] = useState<WalletResponse | null>(wallet)
  const [walletError, setWalletError] = useState('')
  const [payments, setPayments] = useState<PaymentResponse[]>([])
  const [paymentsError, setPaymentsError] = useState('')
  const [isLoadingPayments, setIsLoadingPayments] = useState(true)

  const syncWallet = useCallback((updated: WalletResponse) => {
    setBalance(updated)
    onWalletChange(updated)
  }, [onWalletChange])

  const loadWallet = useCallback(async () => {
    try {
      const loaded = await getWallet(token)
      syncWallet(loaded)
      setWalletError('')
    } catch (caught) {
      setWalletError(describeError(caught))
    }
  }, [token, syncWallet])

  const loadPayments = useCallback(async () => {
    setIsLoadingPayments(true)
    try {
      setPayments(await listPayments(token))
      setPaymentsError('')
    } catch (caught) {
      setPaymentsError(describeError(caught))
    } finally {
      setIsLoadingPayments(false)
    }
  }, [token])

  useEffect(() => {
    void loadWallet()
    void loadPayments()
  }, [loadWallet, loadPayments])

  const balanceValue = balance ? Number(balance.balance) : 0
  const currency = balance?.currency ?? 'USD'
  const completed = payments.filter((payment) => payment.status === 'SUCCESS')
  const recentPayments = payments.slice(0, 4)

  return (
    <div className="dashboard">
      <main className="main-content">
        <header className="top-bar">
          <div className="breadcrumb"><span>Home</span><span>/</span><strong>Overview</strong></div>
          <div className="top-bar-actions">
            <span className="topbar-divider" />
            <ProfileMenu user={user} onTransactions={onTransactions} onSignOut={onSignOut} />
          </div>
        </header>

        <div className="page-body">
          <div className="subpage wallet-home">
            <div className="wallet-greeting">
              <div>
                <span className="eyebrow">YOUR QUICKPAY ACCOUNT</span>
                <h2>Welcome back, {displayHandle(user.username)}</h2>
                <p>Your wallet and recent payment activity, all in one place.</p>
              </div>
              <span className="wallet-handle-chip">{displayHandle(user.username)}</span>
            </div>

            <section className="wallet-overview-card">
              <div className="wallet-overview-copy">
                <span className="wallet-overview-label">AVAILABLE BALANCE</span>
                <strong>{formatAmount(balanceValue, currency)}</strong>
                <span className="wallet-currency-label">
                  {balance?.currency ? `Wallet balance · ${balance.currency}` : 'Wallet not opened yet'}
                </span>
                {walletError && <p className="wallet-error" role="alert">{walletError}</p>}
              </div>
              <div className="wallet-overview-actions">
                <button type="button" className="wallet-light-button" onClick={() => void loadWallet()}>
                  Refresh balance
                </button>
                <button type="button" className="wallet-primary-button" onClick={onPay}>
                  Make a payment <span aria-hidden="true">↗</span>
                </button>
              </div>
              <span className="wallet-card-orbit wallet-card-orbit-one" aria-hidden="true" />
              <span className="wallet-card-orbit wallet-card-orbit-two" aria-hidden="true" />
            </section>

            <section className="wallet-action-grid" aria-label="Quick actions">
              <article className="wallet-action-card">
                <span className="wallet-action-icon topup-icon" aria-hidden="true">＋</span>
                <div>
                  <h3>Add money</h3>
                  <p>{balance?.currency ? `Top up your ${balance.currency} wallet.` : 'Choose a currency as you open your wallet.'}</p>
                </div>
                <TopUpCard token={token} wallet={balance} onWalletChange={syncWallet} />
              </article>
              <button type="button" className="wallet-action-card notification-action" onClick={onNotifications}>
                <span className="wallet-action-icon notification-icon" aria-hidden="true">✉</span>
                <span className="wallet-action-copy">
                  <strong>Notifications</strong>
                  <small>View your inbox and manage email receipt preferences.</small>
                </span>
                <span className="wallet-action-arrow" aria-hidden="true">↗</span>
              </button>
            </section>

            <section className="panel-card wallet-activity-card">
              <div className="panel-heading">
                <div>
                  <h3>Recent activity</h3>
                  <p>{payments.length} payment {payments.length === 1 ? 'record' : 'records'} · {completed.length} completed</p>
                </div>
                <button type="button" className="btn-action" onClick={onTransactions}>
                  View all
                </button>
              </div>

              {paymentsError && <p className="form-error" role="alert">{paymentsError}</p>}
              {!paymentsError && isLoadingPayments && (
                <p className="empty-state">Loading your payment activity…</p>
              )}
              {!paymentsError && !isLoadingPayments && recentPayments.length === 0 && (
                <div className="activity-empty">
                  <span aria-hidden="true">↗</span>
                  <strong>No payments yet</strong>
                  <small>Your payment history will appear here after your first payment.</small>
                </div>
              )}
              {!paymentsError && recentPayments.length > 0 && (
                <ul className="wallet-activity-list">
                  {recentPayments.map((payment) => (
                    <li key={payment.id}>
                      <span className="activity-direction-icon" aria-hidden="true">
                        {payment.direction === 'RECEIVED' ? '↙' : '↗'}
                      </span>
                      <span className="activity-recipient">
                        <strong>
                          {payment.direction === 'RECEIVED'
                            ? `Money received from ${displayHandle(payment.senderHandle)}`
                            : `Sent to ${displayHandle(payment.recipientHandle)}`}
                        </strong>
                        <small>{payment.description || 'Payment'} · {new Date(payment.createdAt).toLocaleDateString()}</small>
                      </span>
                      <span className="activity-amount">
                        {payment.direction === 'RECEIVED' ? '+' : '−'}{formatAmount(payment.amount, payment.currency)}
                      </span>
                      <StatusBadge status={payment.status} />
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </div>
      </main>
    </div>
  )
}
