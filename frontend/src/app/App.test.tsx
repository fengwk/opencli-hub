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
    // The Instances placeholder page heading proves the redirect landed.
    expect(screen.getByRole('heading', { name: 'Instances' })).toBeInTheDocument()
  })

  it('renders all primary navigation entries', () => {
    renderApp('/')
    for (const label of ['Instances', 'Executions', 'Commands', 'Resources', 'Logs']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    }
  })

  it('navigates to a feature route when its nav link is clicked', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('link', { name: 'Executions' }))

    // Heading updates to the Executions placeholder after navigation.
    expect(screen.getByRole('heading', { name: 'Executions' })).toBeInTheDocument()
  })

  it('renders the Commands management page route', () => {
    renderApp('/commands')
    // The page heading proves the route mounts the implemented feature page.
    expect(screen.getByRole('heading', { name: 'Commands' })).toBeInTheDocument()
  })

  it('renders the 404 page for unknown routes', () => {
    renderApp('/does-not-exist')
    expect(screen.getByRole('heading', { name: '404' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回 Instances' })).toBeInTheDocument()
  })
})
