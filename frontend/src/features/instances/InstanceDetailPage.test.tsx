import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InstanceDetailPage } from '@/features/instances/InstanceDetailPage'
import type { HubInstance, HubInstanceVncStatus } from '@/features/instances/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const instanceId = '343020517415976960'
const instance: HubInstance = {
  id: instanceId, code: 'beta', displayName: 'Beta browser', contextId: 'ctx-42', state: 'RUNNING',
  websites: ['demo'], maxPending: 2, lastErrorMessage: null, stateChangedAt: null,
  runtime: { registered: true, displayNumber: 13, vncPort: 5901, activeCount: 0, pendingCount: 0 },
  createTime: null, updateTime: null,
}
const vncStatus: HubInstanceVncStatus = {
  instanceId, instanceAvailable: true, running: true, runtimeAvailable: true, vncAvailable: true,
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/instances/${instanceId}`]}>
        <Routes><Route path="/instances/:id" element={<InstanceDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.clearAllMocks())

describe('InstanceDetailPage', () => {
  it('renders one instance, its VNC availability, and the instance-filtered logs link', async () => {
    // A Snowflake ID above Number.MAX_SAFE_INTEGER proves the detail and VNC routes preserve every decimal digit.
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : instance,
    ) as never)

    renderPage()

    expect(await screen.findByRole('heading', { name: 'Beta browser' })).toBeInTheDocument()
    expect(screen.getByText('ctx-42')).toBeInTheDocument()
    expect(screen.getByText('VNC 可用')).toBeInTheDocument()
    expect(screen.getAllByText('是')).toHaveLength(4)
    expect(screen.getByRole('link', { name: '查看日志' })).toHaveAttribute('href', `/logs?instanceId=${instanceId}`)
    expect(apiClient.get).toHaveBeenCalledWith(`/instances/${instanceId}`)
    expect(apiClient.get).toHaveBeenCalledWith(`/instances/${instanceId}/vnc/status`)
  })

  it('saves edits through the exact update route and refreshes the instance', async () => {
    // Editing checks that all editable M6 properties, including catalog-selected sites, are sent in one PUT payload.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : url === '/opencli/commands' ? [{
        commandKey: 'demo/search', site: 'demo', name: 'search', aliases: null, description: null,
        access: 'READ', browser: true, args: null, siteSession: null, defaultWindowMode: null,
        blacklisted: false, blacklistReason: null, outputRule: null,
      }] : instance,
    ) as never)
    vi.mocked(apiClient.put).mockResolvedValue(instance)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    await user.click(screen.getByRole('button', { name: '编辑' }))
    await screen.findByRole('checkbox', { name: 'demo' })
    await user.clear(screen.getByRole('textbox', { name: '显示名称' }))
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'Updated browser')
    await user.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith(`/instances/${instanceId}`, {
      code: 'beta', displayName: 'Updated browser', websites: ['demo'], maxPending: 2,
    }))
  })
})
