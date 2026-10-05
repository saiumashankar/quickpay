import { useState } from 'react'
import { ApiError, login, register } from '../lib/api'
import { HANDLE_RULE, normalizeHandle } from '../lib/handles'
import { describeError } from '../lib/session'
import type { AuthResponse } from '../lib/types'

export type AuthMode = 'login' | 'register'

interface AuthFormProps {
  mode: AuthMode
  notice?: string
  onModeChange: (mode: AuthMode) => void
  onBack: () => void
  /** Fires after the account is created. No session is started here — the user must log in. */
  onRegistered: (username: string) => void
  /** Fires only after login succeeds. This is where the JWT session begins. */
  onLoggedIn: (result: AuthResponse) => void
}

/**
 * Stage 2 of the flow, in two steps.
 *
 * Register: POST /api/auth/register ({username,email,password}) on auth-service:8081.
 *   The response carries a token, but it is intentionally discarded — the flow always
 *   continues to login so the JWT used for payments is one the user signed into.
 *
 * Login: POST /api/auth/login ({email,password} — email only, no username).
 *   The returned accessToken is the credential for every protected call.
 */
export function AuthForm({
  mode,
  notice,
  onModeChange,
  onBack,
  onRegistered,
  onLoggedIn,
}: AuthFormProps) {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loginEmailEnabled, setLoginEmailEnabled] = useState(false)
  const [loginPasswordEnabled, setLoginPasswordEnabled] = useState(false)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [isSubmitting, setIsSubmitting] = useState(false)

  const isRegister = mode === 'register'

  const switchMode = (next: AuthMode) => {
    setError('')
    setFieldErrors({})
    onModeChange(next)
  }

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError('')
    setFieldErrors({})

    // The username is a handle, and the backend canonicalises it server-side. Sending
    // the canonical form means the account is created under the same spelling that a
    // later transfer will look up.
    if (isRegister && !normalizeHandle(username)) {
      setFieldErrors({ username: HANDLE_RULE })
      return
    }

    setIsSubmitting(true)

    try {
      if (isRegister) {
        const handle = normalizeHandle(username)
        const created = await register({ username: handle ?? username, email, password })
        // Deliberately ignoring `created.accessToken` — see the note above.
        onRegistered(created.user?.username ?? handle ?? username)
        return
      }

      const result = await login({ email, password })
      onLoggedIn(result)
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

  return (
    <div className="auth-page">
      <section className="auth-story">
        <button type="button" className="brand-mark auth-brand" onClick={onBack}>
          <span className="brand-symbol">q</span><span>quickpay</span>
        </button>
        <div className="auth-story-copy">
          <span className="eyebrow light-eyebrow"><span className="live-dot" /> THE FUTURE OF MONEY, IN MOTION</span>
          <h1>Good things happen when money <span>moves.</span></h1>
          <p>One secure place to send, receive, and make sense of every payment.</p>
          <div className="auth-story-note">
            <div className="avatar-stack"><span>J</span><span>M</span><span>A</span></div>
            <span>Trusted by people building what’s next</span>
          </div>
        </div>
        <div className="story-orbit orbit-one" />
        <div className="story-orbit orbit-two" />
        <span className="auth-aside-label">PAYMENTS, WITHOUT THE FRICTION.</span>
      </section>

      <section className="auth-side">
        <button type="button" className="back-link" onClick={onBack}>
          <span aria-hidden="true">←</span> Back to home
        </button>

        <div className="auth-card">
          <span className="eyebrow">{isRegister ? 'STEP 1 OF 3 · SIGN UP' : 'STEP 2 OF 3 · SIGN IN'}</span>
          <h2>{isRegister ? 'Create your account' : 'Welcome back'}</h2>
          <p className="auth-intro">
            {isRegister
              ? 'A smarter way to manage every payment starts here.'
              : 'Sign in to get your secure access token, then start making payments.'}
          </p>

          {notice && !isRegister && (
            <p className="auth-notice" role="status">
              <span aria-hidden="true">✓</span> {notice}
            </p>
          )}

          <form onSubmit={submit} noValidate autoComplete="off">
            {isRegister && (
              <label className="field-label">
                Handle
                <input
                  autoComplete="username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="sai123"
                  minLength={3}
                  maxLength={30}
                  required
                />
                <small className="field-hint">
                  This is your payment handle — people send money to it, not to your email.
                </small>
                {fieldErrors.username && <span className="form-error">{fieldErrors.username}</span>}
              </label>
            )}

            <label className="field-label">
              Email address
              <input
                autoComplete={isRegister ? 'email' : 'off'}
                type="email"
                value={email}
                readOnly={!isRegister && !loginEmailEnabled}
                onFocus={() => setLoginEmailEnabled(true)}
                onChange={(event) => setEmail(event.target.value)}
                placeholder={isRegister ? 'you@example.com' : 'Enter your email address'}
                required
              />
              {fieldErrors.email && <span className="form-error">{fieldErrors.email}</span>}
            </label>

            <label className="field-label">
              Password
              <input
                autoComplete={isRegister ? 'new-password' : 'off'}
                type="password"
                value={password}
                readOnly={!isRegister && !loginPasswordEnabled}
                onFocus={() => setLoginPasswordEnabled(true)}
                onChange={(event) => setPassword(event.target.value)}
                placeholder={isRegister ? 'At least 6 characters' : 'Enter your password'}
                minLength={6}
                required
              />
              {fieldErrors.password && <span className="form-error">{fieldErrors.password}</span>}
            </label>

            {error && <p className="form-error" role="alert">{error}</p>}

            <button type="submit" className="btn-primary auth-submit" disabled={isSubmitting}>
              {isSubmitting
                ? 'Please wait…'
                : isRegister
                  ? 'Create account'
                  : 'Sign in'}
              <span aria-hidden="true">↗</span>
            </button>
          </form>

          <p className="auth-switch">
            {isRegister ? 'Already have an account?' : 'New to QuickPay?'}{' '}
            <button type="button" onClick={() => switchMode(isRegister ? 'login' : 'register')}>
              {isRegister ? 'Sign in' : 'Create an account'}
            </button>
          </p>
          <p className="auth-legal">
            By continuing, you agree to our <a href="#terms">Terms</a> and <a href="#privacy">Privacy Policy</a>.
          </p>
        </div>

        <span className="auth-security"><span aria-hidden="true">◈</span> Your information is encrypted and secure</span>
      </section>
    </div>
  )
}