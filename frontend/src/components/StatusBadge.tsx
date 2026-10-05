import type { PaymentStatus } from '../lib/types'

interface StatusBadgeProps {
  status: PaymentStatus
}

/** Maps the backend's uppercase PaymentStatus onto the lowercase CSS modifier classes. */
export function StatusBadge({ status }: StatusBadgeProps) {
  return (
    <span className={`status-badge ${status.toLowerCase()}`}>
      <i />{status.toLowerCase()}
    </span>
  )
}