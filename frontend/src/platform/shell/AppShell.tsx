import { Menu } from 'lucide-react'
import { useState } from 'react'
import type { PropsWithChildren } from 'react'
import { Link, NavLink } from 'react-router-dom'
import { navItems } from '@/platform/shell/nav-items'

/**
 * Responsive application shell: a persistent sidebar on wide viewports that
 * collapses behind a toggle on narrow ones, plus a top bar with brand and the
 * mobile navigation toggle. Layout only — no business data.
 */
export function AppShell({ children }: PropsWithChildren) {
  const [navOpen, setNavOpen] = useState(false)

  return (
    <div className="app-frame" data-nav-open={navOpen}>
      <header className="topbar">
        <button
          type="button"
          className="nav-toggle"
          aria-label="切换导航"
          aria-expanded={navOpen}
          onClick={() => setNavOpen((open) => !open)}
        >
          <Menu aria-hidden="true" />
        </button>
        <Link to="/instances" className="brand" onClick={() => setNavOpen(false)}>
          OpenCLI Hub
        </Link>
      </header>

      <div className="app-body">
        <nav className="sidebar" aria-label="Primary">
          {navItems.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
              onClick={() => setNavOpen(false)}
            >
              <Icon aria-hidden="true" className="nav-icon" />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>

        <main className="stage">{children}</main>
      </div>
    </div>
  )
}
