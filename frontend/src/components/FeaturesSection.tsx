const features = [
  {
    number: '01',
    icon: '@',
    tone: 'icon-secure',
    title: 'Pay by handle',
    description: 'Send a payment using a person’s QuickPay handle—no account number needed in the form.',
    detail: 'Handle-based payments',
  },
  {
    number: '02',
    icon: '◉',
    tone: 'icon-instant',
    title: 'A wallet that stays clear',
    description: 'See your available balance and the currency your wallet uses before you send.',
    detail: 'Balance at a glance',
  },
  {
    number: '03',
    icon: '↗',
    tone: 'icon-notify',
    title: 'A payment record',
    description: 'Review payment references, amounts, dates and statuses in your payment history.',
    detail: 'History with status',
  },
  {
    number: '04',
    icon: '＋',
    tone: 'icon-secure',
    title: 'Top up your wallet',
    description: 'Add funds and choose a supported currency as your wallet opens. Its currency stays fixed.',
    detail: 'One wallet currency',
  },
  {
    number: '05',
    icon: '✉',
    tone: 'icon-instant',
    title: 'Email receipts',
    description: 'Payment receipts are sent through the notification service to the email on your account.',
    detail: 'Payment notifications',
  },
  {
    number: '06',
    icon: '◌',
    tone: 'icon-notify',
    title: 'Choose email preferences',
    description: 'Turn email notifications on or off from your signed-in dashboard.',
    detail: 'Your preference, your call',
  },
]

const steps = [
  { number: '01', title: 'Create your account', text: 'Register with an email and choose your payment handle.' },
  { number: '02', title: 'Set up your wallet', text: 'Top up to open your wallet and choose its currency.' },
  { number: '03', title: 'Send and keep track', text: 'Pay by handle, then find the status in your history.' },
]

export function FeaturesSection() {
  return (
    <>
      <section className="features-section" id="features">
        <div className="features-heading">
          <span className="eyebrow">QUICKPAY, AT A GLANCE</span>
          <h2 className="section-title">The essentials for<br /><span>everyday payments.</span></h2>
          <p>A focused set of tools to send, fund and follow your payments.</p>
        </div>
        <div className="features-grid">
          {features.map((feature) => (
            <article className="feature-card" key={feature.number}>
              <div className={`feature-icon ${feature.tone}`} aria-hidden="true">{feature.icon}</div>
              <span className="feature-number">{feature.number}</span>
              <h3>{feature.title}</h3>
              <p>{feature.description}</p>
              <span className="feature-link">{feature.detail}<span aria-hidden="true">↗</span></span>
            </article>
          ))}
        </div>
        <div className="security-strip" id="security">
          <span className="security-icon" aria-hidden="true">✓</span>
          <strong>Know where each payment stands.</strong>
          <span>Every payment has a reference and a status you can review in your history.</span>
        </div>
      </section>

      <section className="how-section" id="how">
        <div className="how-heading">
          <span className="eyebrow">THREE CLEAR STEPS</span>
          <h2 className="section-title">From sign-in to <span>sent.</span></h2>
          <p>Start with an account, set up your wallet, and send to a handle.</p>
        </div>
        <div className="how-grid">
          {steps.map((step) => (
            <article className="how-card" key={step.number}>
              <span className="how-number">{step.number}</span>
              <h3>{step.title}</h3>
              <p>{step.text}</p>
            </article>
          ))}
        </div>
      </section>
    </>
  )
}