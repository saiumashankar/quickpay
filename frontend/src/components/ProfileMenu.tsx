import { useEffect, useRef, useState } from 'react'
import { displayHandle } from '../lib/handles'
import type { UserResponse } from '../lib/types'

interface ProfileMenuProps {
  user: UserResponse
  onTransactions: () => void
  /** Offered as an explicit choice inside the menu, never as the click on the chip itself. */
  onSignOut: () => void
}

/**
 * The account control in the top bar.
 *
 * This used to be a bare button wired straight to sign out, so clicking your own name
 * logged you out with no warning and no way back. Opening the chip now reveals who you
 * are signed in as, and signing out is one deliberate step inside the menu.
 */
export function ProfileMenu({ user, onTransactions, onSignOut }: ProfileMenuProps) {
  const [isOpen, setIsOpen] = useState(false)
  const container = useRef<HTMLDivElement>(null)

  // Dismiss on an outside click or Escape, which is what a menu is expected to do once
  // it is open. Without this the only way to close it is to open it again.
  useEffect(() => {
    if (!isOpen) return

    const onPointerDown = (event: MouseEvent) => {
      if (container.current && !container.current.contains(event.target as Node)) {
        setIsOpen(false)
      }
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsOpen(false)
    }

    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [isOpen])

  return (
    <div className="profile-wrap" ref={container}>
      <button
        type="button"
        className="topbar-profile"
        aria-haspopup="menu"
        aria-expanded={isOpen}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span className="user-avatar">{initials(user.username)}</span>
        <span>
          <strong>{displayHandle(user.username)}</strong>
          <small>{user.email}</small>
        </span>
        <span className="profile-caret" aria-hidden="true">{isOpen ? '▴' : '▾'}</span>
      </button>

      {isOpen && (
        <div className="profile-menu" role="menu">
          <div className="profile-menu-head">
            <span className="user-avatar">{initials(user.username)}</span>
            <span>
              <strong>{displayHandle(user.username)}</strong>
              <small>{user.email}</small>
            </span>
          </div>

          <dl className="profile-menu-details">
            <div><dt>Role</dt><dd>{user.role}</dd></div>
            <div><dt>Status</dt><dd>{user.status}</dd></div>
            <div><dt>Joined</dt><dd>{new Date(user.createdAt).toLocaleDateString()}</dd></div>
          </dl>

          <button
            type="button"
            className="profile-menu-action profile-menu-transactions"
            role="menuitem"
            onClick={() => {
              setIsOpen(false)
              onTransactions()
            }}
          >
            <span><strong>Transactions</strong><small>View payment activity</small></span>
            <span aria-hidden="true">↗</span>
          </button>
          <button
            type="button"
            className="profile-menu-action"
            role="menuitem"
            onClick={() => {
              setIsOpen(false)
              onSignOut()
            }}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}

function initials(value: string): string {
  return value.trim().slice(0, 2).toUpperCase() || 'QP'
}