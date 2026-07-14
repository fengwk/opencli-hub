import { Menu, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { PropsWithChildren } from 'react'
import { Link, NavLink } from 'react-router-dom'
import { navItems } from '@/platform/shell/nav-items'

/** Responsive application shell with a desktop top navigation and mobile menu. */
export function AppShell({ children }: PropsWithChildren) {
  const [navOpen, setNavOpen] = useState(false)
  const navToggleRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!navOpen) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavOpen(false)
        navToggleRef.current?.focus()
      }
    }
    document.addEventListener('keydown', closeOnEscape)
    return () => document.removeEventListener('keydown', closeOnEscape)
  }, [navOpen])

  return (
    <div className="app-frame" data-nav-open={navOpen}>
      <header className="topbar">
        <Link to="/instances" className="brand" onClick={() => setNavOpen(false)}>
          <span className="brand-mark" aria-hidden="true">O</span>
          <span>OpenCLI Hub</span>
          <small>CONTROL ROOM</small>
        </Link>
        <button
          ref={navToggleRef}
          type="button"
          className="nav-toggle"
          aria-label={navOpen ? '关闭导航' : '打开导航'}
          aria-expanded={navOpen}
          aria-controls="primary-navigation"
          onClick={() => setNavOpen((open) => !open)}
        >
          {navOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
        </button>
        <nav id="primary-navigation" className="top-nav" aria-label="主导航">
          {navItems.map(({ to, label, technicalLabel, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              aria-label={label}
              className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
              onClick={() => setNavOpen(false)}
            >
              <Icon aria-hidden="true" className="nav-icon" />
              <span>{label}</span>
              <small aria-hidden="true">{technicalLabel}</small>
            </NavLink>
          ))}
        </nav>
        <div className="shell-status" aria-label="控制台状态">
          <span aria-hidden="true" />
          管理控制台
        </div>
      </header>
      <main className="stage">{children}</main>
    </div>
  )
}
