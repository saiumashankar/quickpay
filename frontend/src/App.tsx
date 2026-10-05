import { AuthForm } from './components/AuthForm'
import { DashboardPage } from './components/DashboardPage'
import { NotificationsPage } from './components/NotificationsPage'
import { PaymentPage } from './components/PaymentPage'
import { StartPage } from './components/StartPage'
import { TransactionsPage } from './components/TransactionsPage'
import { useSession } from './lib/session'

/**
 * The app is a fixed sequence, driven entirely by `stage`:
 *
 *   start -> register -> login -> overview -> payment -> notifications
 *                                      \-> transactions (from the profile menu)
 *
 * Registration creates the account but does not sign in. The JWT that authorises
 * payments, transactions and notifications is issued by login, so the payment stage
 * cannot be reached without a token the backend has actually verified.
 */
export default function App() {
  const session = useSession()
  const {
    stage,
    token,
    user,
    payment,
    wallet,
    isRefreshing,
    refreshError,
    retryRefresh,
    notice,
    goToLogin,
    goToRegister,
    completeRegistration,
    completeLogin,
    goToPayment,
    goToOverview,
    goToTransactions,
    goToNotifications,
    completePayment,
    backToPayment,
    updateWallet,
    signOut,
  } = session

  const switchAuthMode = (mode: 'login' | 'register') => {
    if (mode === 'register') goToRegister()
    else goToLogin()
  }

  // Stage 1 — the start page, offering the two ways in.
  if (stage === 'start') {
    return <StartPage onLogin={goToLogin} onRegister={goToRegister} />
  }

  // Stages 2 and 3 — registration, then login. The login step is what issues the JWT.
  if (stage === 'register' || stage === 'login') {
    return (
      <AuthForm
        key={stage}
        mode={stage === 'register' ? 'register' : 'login'}
        notice={notice}
        onModeChange={switchAuthMode}
        onBack={signOut}
        onRegistered={completeRegistration}
        onLoggedIn={completeLogin}
      />
    )
  }

  // Everything past login requires a verified token and the user it belongs to.
  if (!token || !user) return null

  if (refreshError) {
    return (
      <div className="session-recovery">
        <div className="session-recovery-card">
          <span className="brand-symbol">q</span>
          <span className="eyebrow">SESSION RESTORE</span>
          <h1>You're still signed in.</h1>
          <p>QuickPay couldn't reconnect right now. Your session is saved; try again when the service is available.</p>
          <div className="session-recovery-actions">
            <button type="button" className="btn-primary" onClick={retryRefresh}>Try again</button>
            <button type="button" className="btn-text" onClick={signOut}>Sign out</button>
          </div>
        </div>
      </div>
    )
  }

  // The access token is inside its refresh window. Hold the page rather than letting
  // a request go out that is certain to come back 401 and sign the user out.
  if (isRefreshing) {
    return (
      <div className="dashboard">
        <main className="main-content">
          <div className="page-body">
            <div className="subpage">
              <p className="empty-state">Renewing your session…</p>
            </div>
          </div>
        </main>
      </div>
    )
  }

  // Stage 4 — the signed-in overview: balance, transfers, and the way into a payment.
  if (stage === 'overview') {
    return (
      <DashboardPage
        token={token}
        user={user}
        wallet={wallet}
        onWalletChange={updateWallet}
        onPay={goToPayment}
        onNotifications={goToNotifications}
        onTransactions={goToTransactions}
        onSignOut={signOut}
      />
    )
  }

  // Stage 5 — make a payment.
  if (stage === 'payment') {
    return (
      <PaymentPage
        token={token}
        user={user}
        wallet={wallet}
        onWalletChange={updateWallet}
        onPaid={completePayment}
        onBack={goToOverview}
        onTransactions={goToTransactions}
        onSignOut={signOut}
      />
    )
  }

  // Stage 6 — reached only when the backend reported the payment as SUCCESS.
  if (stage === 'notifications') {
    return (
      <NotificationsPage
        token={token}
        user={user}
        payment={payment}
        wallet={wallet}
        onPayAgain={backToPayment}
        onBack={goToOverview}
        onTransactions={goToTransactions}
        onSignOut={signOut}
      />
    )
  }

  if (stage === 'transactions') {
    return (
      <TransactionsPage
        token={token}
        user={user}
        onBack={goToOverview}
        onTransactions={goToTransactions}
        onSignOut={signOut}
      />
    )
  }

  return null
}