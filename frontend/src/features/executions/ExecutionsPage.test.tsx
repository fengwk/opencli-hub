import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ExecutionsPage } from '@/features/executions/ExecutionsPage'
import type { HubExecution } from '@/features/executions/types'
import { apiClient } from '@/shared/api/client'
import type { PageResult } from '@/shared/api/contracts'

vi.mock('@/shared/api/client', () => ({
  apiBaseUrl: '/api',
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

const execution: HubExecution = {
  id: 101,
  instanceId: 7,
  instanceCode: 'chrome-7',
  commandKey: 'demo/search',
  site: 'demo',
  siteSession: 'EPHEMERAL',
  reuseInstance: false,
  argv: ['demo/search', 'keyword'],
  status: 'SUCCEEDED',
  exitCode: 0,
  stdout: '{"ok":true}',
  stdoutTruncated: false,
  stderr: null,
  stderrTruncated: false,
  errorMessage: null,
  timeoutMillis: 30000,
  queuedMillis: 12,
  durationMillis: 340,
  resources: [],
  queuedAt: '2026-07-13T10:00:00',
  startedAt: '2026-07-13T10:00:01',
  finishedAt: '2026-07-13T10:00:02',
}

function page(results: HubExecution[], totalCount = results.length): PageResult<HubExecution> {
  return { pageNumber: 1, pageSize: 20, totalCount, results }
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ExecutionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.clearAllMocks())

describe('ExecutionsPage', () => {
  it('uses 1-based pagination and sends only a positive submitted Instance filter', async () => {
    // Each interaction asserts the exact M6 query parameters, including the reset to page 1 on filter/page-size changes.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue(page([execution], 45))

    renderPage()
    await screen.findByText('demo/search')
    expect(apiClient.get).toHaveBeenLastCalledWith('/executions', { params: { pageNumber: 1, pageSize: 20 } })

    await user.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(apiClient.get).toHaveBeenLastCalledWith('/executions', {
      params: { pageNumber: 2, pageSize: 20 },
    }))

    await user.type(screen.getByRole('textbox', { name: 'Instance ID' }), '7')
    await user.click(screen.getByRole('button', { name: '筛选' }))
    await waitFor(() => expect(apiClient.get).toHaveBeenLastCalledWith('/executions', {
      params: { pageNumber: 1, pageSize: 20, instanceId: 7 },
    }))

    await user.selectOptions(screen.getByRole('combobox', { name: '每页数量' }), '50')
    await waitFor(() => expect(apiClient.get).toHaveBeenLastCalledWith('/executions', {
      params: { pageNumber: 1, pageSize: 50, instanceId: 7 },
    }))
  })

  it('renders all terminal execution statuses with their intended tones', async () => {
    // Status badge tones distinguish successful, failed, and timed-out terminal records in the history table.
    vi.mocked(apiClient.get).mockResolvedValue(page([
      execution,
      { ...execution, id: 102, status: 'FAILED' },
      { ...execution, id: 103, status: 'TIMED_OUT' },
    ]))

    renderPage()

    expect((await screen.findByText('SUCCEEDED')).dataset.tone).toBe('success')
    expect(screen.getByText('FAILED').dataset.tone).toBe('danger')
    expect(screen.getByText('TIMED_OUT').dataset.tone).toBe('warning')
  })

  it('shows a retryable error instead of leaving a failed history request blank', async () => {
    // A rejected list request must remain actionable so operators can recover the execution history view.
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('history unavailable'))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('history unavailable')
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })
})
