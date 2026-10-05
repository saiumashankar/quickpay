interface HeroSectionProps {
  onLogin: () => void
  onRegister: () => void
}

export function HeroSection({ onLogin, onRegister }: HeroSectionProps) {
  return (
    <section className="hero-section">
      <div className="hero-copy">
        <span className="eyebrow"><span className="live-dot" /> A CLEARER WAY TO PAY</span>
        <h1 className="tagline">Send by handle.<br /><span>Stay in the know.</span></h1>
        <p className="sub-tagline">QuickPay brings handle-based payments, wallet balance, payment history and email receipts into one simple place.</p>
        <div className="hero-actions">
          <button type="button" className="cta-button" onClick={onRegister}>Create an account <span aria-hidden="true">↗</span></button>
          <button className="btn-text" onClick={onLogin}>Sign in</button>
        </div>
        <div className="hero-proof">
          <div className="proof-item"><strong>Handle payments</strong><span>send without account numbers</span></div>
          <span className="proof-divider" />
          <div className="proof-item"><strong>Payment history</strong><span>references and statuses</span></div>
          <span className="proof-divider" />
          <div className="proof-item"><strong>Email receipts</strong><span>preferences you can manage</span></div>
        </div>
      </div>
      <div className="hero-visual" aria-label="Illustration of a QuickPay wallet and payment">
        <div className="visual-glow" />
        <div className="payment-card">
          <div className="payment-card-top"><span className="mini-brand"><span className="brand-symbol">q</span> quickpay</span><span className="card-dots">ILLUSTRATION</span></div>
          <span className="payment-label">WALLET BALANCE · EXAMPLE</span>
          <strong className="balance-value">$240<span>.00</span></strong>
          <span className="balance-change"><span>✓</span> Payment status <span className="muted-text">visible in history</span></span>
          <div className="card-footer"><span>PAYMENT REFERENCE</span><span>QP</span></div>
        </div>
        <div className="transfer-popover"><span className="transfer-icon">↗</span><span><strong>Payment example</strong><small>to @alex · illustration</small></span><strong className="transfer-amount">−$24.00</strong></div>
        <div className="secure-chip">WALLET OVERVIEW</div>
      </div>
    </section>
  )
}