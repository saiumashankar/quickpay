import { FeaturesSection } from './FeaturesSection'
import { HeroSection } from './HeroSection'

interface StartPageProps {
  onLogin: () => void
  onRegister: () => void
}

/**
 * Stage 1 of the flow — the app's entry point. Its only jobs are to explain the
 * product and offer the two ways in: login or register.
 */
export function StartPage({ onLogin, onRegister }: StartPageProps) {
  return (
    <div className="landing-page">
      <header className="landing-header">
        <span className="brand-mark">
          <span className="brand-symbol">q</span><span>quickpay</span>
        </span>
        <nav className="landing-nav">
          <a href="#features">Features</a>
          <a href="#security">Security</a>
          <a href="#how">How it works</a>
        </nav>
        <div className="header-right">
          <button type="button" className="btn-text" onClick={onLogin}>Login</button>
          <button type="button" className="btn-primary" onClick={onRegister}>
            Signup <span aria-hidden="true">↗</span>
          </button>
        </div>
      </header>

      <HeroSection onLogin={onLogin} onRegister={onRegister} />
      <FeaturesSection />

      <footer className="landing-footer">
        <span className="footer-brand"><span className="brand-symbol">q</span> quickpay</span>
        <span>Handle-based payments, wallet balance and email receipts.</span>
        <span className="landing-footer-note">QuickPay · Payment platform</span>
      </footer>
    </div>
  )
}