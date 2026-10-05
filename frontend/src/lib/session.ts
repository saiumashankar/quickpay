import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, refreshSession, setUnauthorizedHandler } from './api'
import type { AuthResponse, PaymentResponse, UserResponse, WalletResponse } from './types'

/**
 * The app is a strict sequence:
 *
 *   start -> register -> login -> overview -> payment -> notifications
 *                                      \-> transactions
 *
 * Registration deliberately does NOT authenticate. The backend returns a token from
 * POST /api/auth/register, but we discard it and send the user to login, so the JWT
 * that authorises payments, transactions and notifications is always one the user
 * actually proved they own by signing in.
 *
 * `overview` sits between login and payment because signing in is not the same intent
 * as sending money. It is the signed-in home: balance, the full transfer history, and
 * the notification preference, with "make a payment" as one deliberate step away.
 *
 * `stage` encodes that order and every transition happens here, so the sequence
 * cannot be skipped or reordered. Transactions are reachable from the profile menu.
 */
export type Stage = 'start' | 'register' | 'login' | 'overview' | 'payment' | 'notifications' | 'transactions'

const SESSION_KEY = 'quickpay.session'

/** Refresh this long before expiry, so an in-flight request cannot race the deadline. */
const REFRESH_MARGIN_MS = 60_000

interface StoredSession {
  accessToken: string
  refreshToken: string
  expiresAt: string
  user: UserResponse
  payment: PaymentResponse | null
  wallet: WalletResponse | null
  stage?: Stage
}

function parse(raw: string | null): StoredSession | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as StoredSession
    if (!parsed?.accessToken || !parsed?.refreshToken) return null
    const stage: Stage = parsed.stage === 'payment'
      || parsed.stage === 'notifications'
      || parsed.stage === 'transactions'
      ? parsed.stage
      : 'overview'
    // An older stored session may predate the wallet field; treat it as "not loaded yet".
    return { ...parsed, stage, wallet: parsed.wallet ?? null }
  } catch {
    return null
  }
}

function loadSession(): StoredSession | null {
  const parsed = parse(localStorage.getItem(SESSION_KEY))
  if (!parsed) return null

  return parsed
}

function needsRefresh(session: StoredSession | null): boolean {
  if (!session) return false
  const expiresAt = new Date(session.expiresAt).getTime()
  return !Number.isFinite(expiresAt) || expiresAt - Date.now() <= REFRESH_MARGIN_MS
}

export interface Session {
  stage: Stage
  token: string | null
  user: UserResponse | null
  payment: PaymentResponse | null
  wallet: WalletResponse | null
  /** True while an expired session is being renewed. Pages wait rather than 401. */
  isRefreshing: boolean
  /** A transient refresh failure keeps the saved session instead of signing out. */
  refreshError: string
  /** Shown on the login form after a successful registration. */
  notice: string

  /** Stage 1 -> 2. Both entry points land on login; signup routes via register. */
  goToLogin: () => void
  goToRegister: () => void
  /** Stage 2 -> 3. Registration only creates the account; it does not sign the user in. */
  completeRegistration: (username: string) => void
  /** Stage 3 -> 4. This is where the JWT session is actually established. */
  completeLogin: (result: AuthResponse) => void
  /** Overview -> payment. The only way into the payment form. */
  goToPayment: () => void
  /** Payment -> overview, for going back without signing out. */
  goToOverview: () => void
  /** Stage 4 -> 5. Only reached by a payment the backend reported as SUCCESS. */
  completePayment: (payment: PaymentResponse, wallet: WalletResponse | null) => void
  /** Returns to the payment stage to make another payment. */
  backToPayment: () => void
  /** Opens the signed-in user's complete payment history. */
  goToTransactions: () => void
  /** Opens notification preferences and the delivered-email inbox. */
  goToNotifications: () => void
  /** Keeps the displayed balance in step with the backend after a transfer. */
  updateWallet: (wallet: WalletResponse) => void
  retryRefresh: () => void
  signOut: () => void
}

export function useSession(): Session {
  const [restoredSession] = useState(() => loadSession())
  const [session, setSession] = useState<StoredSession | null>(restoredSession)
  const [stage, setStage] = useState<Stage>(
    () => restoredSession?.stage ?? (restoredSession ? 'overview' : 'start'),
  )
  const [notice, setNotice] = useState('')
  const [isRefreshing, setIsRefreshing] = useState(() => (
    needsRefresh(restoredSession)
  ))
  const [refreshError, setRefreshError] = useState('')
  // Guards against two overlapping refreshes when several requests notice the expiry.
  const refreshInFlight = useRef(false)

  useEffect(() => {
    if (session) {
      localStorage.setItem(SESSION_KEY, JSON.stringify({ ...session, stage }))
    } else {
      localStorage.removeItem(SESSION_KEY)
    }
  }, [session, stage])

  const signOut = useCallback(() => {
    setSession(null)
    setNotice('')
    setIsRefreshing(false)
    setRefreshError('')
    setStage('start')
  }, [])

  const handleUnauthorized = useCallback(() => {
    setRefreshError('')
    setIsRefreshing(true)
  }, [])

  // Renew a rejected access token before dropping the saved session. A refresh token
  // rejection itself still signs out in the refresh effect below.
  useEffect(() => {
    setUnauthorizedHandler(handleUnauthorized)
    return () => setUnauthorizedHandler(null)
  }, [handleUnauthorized])

  const applyAuthResponse = useCallback((result: AuthResponse) => {
    setSession((current) => ({
      accessToken: result.accessToken,
      refreshToken: result.refreshToken,
      expiresAt: result.expiresAt,
      user: result.user,
      // A refresh is not a new payment and a re-login is not either, so both keep
      // whatever the backend already told us about the wallet and the last transfer.
      payment: current?.payment ?? null,
      wallet: current?.wallet ?? null,
    }))
  }, [])

  // Schedule renewal before expiry, and block protected pages while restoring an
  // already-expired session after reload.
  useEffect(() => {
    if (!session?.refreshToken) return

    const remaining = new Date(session.expiresAt).getTime() - Date.now()
    if (!isRefreshing && refreshError) return

    if (!isRefreshing && remaining > REFRESH_MARGIN_MS) {
      const timer = window.setTimeout(
        () => setIsRefreshing(true),
        remaining - REFRESH_MARGIN_MS,
      )
      return () => window.clearTimeout(timer)
    }

    if (refreshInFlight.current) return
    refreshInFlight.current = true
    setIsRefreshing(true)

    refreshSession(session.refreshToken)
      .then(applyAuthResponse)
      .catch((caught: unknown) => {
        if (caught instanceof ApiError && (caught.status === 400 || caught.status === 401)) {
          signOut()
        } else {
          setRefreshError(describeError(caught))
        }
      })
      .finally(() => {
        refreshInFlight.current = false
        setIsRefreshing(false)
      })
  }, [session, isRefreshing, refreshError, applyAuthResponse, signOut])

  const retryRefresh = useCallback(() => {
    setRefreshError('')
    setIsRefreshing(true)
  }, [])

  const goToLogin = useCallback(() => {
    setNotice('')
    setStage('login')
  }, [])

  const goToRegister = useCallback(() => {
    setNotice('')
    setStage('register')
  }, [])

  const completeRegistration = useCallback((username: string) => {
    // Registration returns a token, but we ignore it: the account must be signed into
    // before it can act. Move to login and confirm the account was created.
    setNotice(
      `Account created for @${username}. Sign in to continue to payments.`,
    )
    setStage('login')
  }, [])

  const completeLogin = useCallback((result: AuthResponse) => {
    setNotice('')
    setSession({
      accessToken: result.accessToken,
      refreshToken: result.refreshToken,
      expiresAt: result.expiresAt,
      user: result.user,
      payment: null,
      wallet: null,
      stage: 'overview',
    })
    // Land on the overview rather than the payment form: signing in is not a request
    // to send money, and the overview is where the balance and history belong.
    setStage('overview')
  }, [])

  const goToPayment = useCallback(() => {
    setStage('payment')
  }, [])

  const goToOverview = useCallback(() => {
    setStage('overview')
  }, [])

  const completePayment = useCallback((payment: PaymentResponse, wallet: WalletResponse | null) => {
    setSession((current) => (current ? { ...current, payment, wallet, stage: 'notifications' } : current))
    setStage('notifications')
  }, [])

  const backToPayment = useCallback(() => {
    setStage('payment')
  }, [])

  const goToTransactions = useCallback(() => {
    setStage('transactions')
  }, [])

  const goToNotifications = useCallback(() => {
    setSession((current) => (current ? { ...current, payment: null } : current))
    setStage('notifications')
  }, [])

  const updateWallet = useCallback((wallet: WalletResponse) => {
    setSession((current) => (current ? { ...current, wallet } : current))
  }, [])

  return useMemo(
    () => ({
      stage,
      token: session?.accessToken ?? null,
      user: session?.user ?? null,
      payment: session?.payment ?? null,
      wallet: session?.wallet ?? null,
      isRefreshing,
      refreshError,
      notice,
      goToLogin,
      goToRegister,
      completeRegistration,
      completeLogin,
      goToPayment,
      goToOverview,
      completePayment,
      backToPayment,
      goToTransactions,
      goToNotifications,
      updateWallet,
      retryRefresh,
      signOut,
    }),
    [
      stage,
      session,
      isRefreshing,
      refreshError,
      notice,
      goToLogin,
      goToRegister,
      completeRegistration,
      completeLogin,
      goToPayment,
      goToOverview,
      completePayment,
      backToPayment,
      goToTransactions,
      goToNotifications,
      updateWallet,
      retryRefresh,
      signOut,
    ],
  )
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return 'Something went wrong. Please try again.'
}