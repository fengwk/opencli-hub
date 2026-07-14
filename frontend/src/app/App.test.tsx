import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'

function renderApp(initialPath = '/') {
  window.history.replaceState({}, '', initialPath)
  return render(
    <AppProviders>
      <App />
    </AppProviders>,
  )
}

describe('App shell and routing', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/')
  })

  it('redirects the root route to /instances', () => {
    renderApp('/')
    // The instances management heading proves the redirect landed.
    expect(screen.getByRole('heading', { name: '实例管理' })).toBeInTheDocument()
  })

  it('renders all primary navigation entries', () => {
    renderApp('/')
    for (const label of ['实例管理', '执行记录', '命令目录', '资源中心', '系统设置', '日志中心']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    }
  })

  it('navigates to a feature route when its nav link is clicked', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('link', { name: '执行记录' }))

    // Heading updates to prove the executions route mounted.
    expect(screen.getByRole('heading', { name: '执行记录' })).toBeInTheDocument()
  })

  it('toggles and dismisses the mobile navigation with the keyboard', async () => {
    // The menu remains operable when CSS exposes it at the mobile breakpoint.
    const user = userEvent.setup()
    renderApp('/')
    const toggle = screen.getByRole('button', { name: '打开导航' })

    await user.click(toggle)
    expect(screen.getByRole('button', { name: '关闭导航' })).toHaveAttribute('aria-expanded', 'true')

    await user.keyboard('{Escape}')
    const closedToggle = screen.getByRole('button', { name: '打开导航' })
    expect(closedToggle).toHaveAttribute('aria-expanded', 'false')
    expect(closedToggle).toHaveFocus()
  })

  it('renders the Commands management page route', () => {
    renderApp('/commands')
    // The page heading proves the route mounts the implemented feature page.
    expect(screen.getByRole('heading', { name: '命令目录' })).toBeInTheDocument()
  })

  it('renders the system settings route', () => {
    renderApp('/settings')
    // The settings heading proves the top-level route resolves before its query completes.
    expect(screen.getByRole('heading', { name: '系统设置' })).toBeInTheDocument()
  })

  it('renders the 404 page for unknown routes', () => {
    renderApp('/does-not-exist')
    expect(screen.getByRole('heading', { name: '404' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回实例管理' })).toBeInTheDocument()
  })
})
