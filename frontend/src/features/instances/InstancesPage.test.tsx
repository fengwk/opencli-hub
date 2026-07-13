import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InstancesPage } from '@/features/instances/InstancesPage'
import type { HubCommand } from '@/features/commands/types'
import type { HubInstance } from '@/features/instances/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const command: HubCommand = {
  commandKey: 'demo/search', site: 'demo', name: 'search', aliases: null, description: null,
  access: 'READ', browser: true, args: null, siteSession: 'PERSISTENT', defaultWindowMode: null,
  blacklisted: false, blacklistReason: null, outputRule: null,
}

const stoppedInstance: HubInstance = {
  id: 7, code: 'alpha', displayName: 'Alpha browser', contextId: null, state: 'STOPPED',
  websites: ['demo'], maxPending: 3, lastErrorMessage: 'last launch failed', stateChangedAt: null,
  runtime: { registered: false, displayNumber: null, vncPort: null, activeCount: 0, pendingCount: 0 },
  createTime: null, updateTime: null,
}

function mockCatalogAndInstances(instances: HubInstance[]) {
  vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
    url === '/instances' ? instances : [command],
  ) as never)
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <BrowserRouter><InstancesPage /></BrowserRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.clearAllMocks())

describe('InstancesPage', () => {
  it('shows loading, then renders the instance runtime, queue, error, and detail navigation', async () => {
    // Delaying only the list response proves the page has an explicit load state before showing its DTO fields.
    let resolveInstances: ((value: HubInstance[]) => void) | undefined
    vi.mocked(apiClient.get).mockImplementation((url: string) => {
      if (url === '/instances') {
        return new Promise((resolve) => { resolveInstances = resolve }) as never
      }
      return Promise.resolve([command]) as never
    })

    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载实例')

    resolveInstances?.([stoppedInstance])
    expect(await screen.findByRole('heading', { name: 'Alpha browser' })).toBeInTheDocument()
    expect(screen.getByText('未注册')).toBeInTheDocument()
    expect(screen.getByText(/活跃 0 \/ 待处理 0/)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('最近错误：last launch failed')
    expect(screen.getByRole('link', { name: '查看详情' })).toHaveAttribute('href', '/instances/7')
  })

  it('surfaces a list transport error instead of leaving the page empty', async () => {
    // A rejected list request must give operators a retryable failure state.
    vi.mocked(apiClient.get).mockImplementation((url: string) => (
      url === '/instances' ? Promise.reject(new Error('network unavailable')) : Promise.resolve([command])
    ) as never)

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('network unavailable')
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })

  it('sends the create DTO and keeps the form visibly pending until synchronous creation finishes', async () => {
    // The unresolved POST verifies the create button cannot be submitted twice while M6 creates the runtime synchronously.
    const user = userEvent.setup()
    let resolveCreate: ((value: HubInstance) => void) | undefined
    mockCatalogAndInstances([])
    vi.mocked(apiClient.post).mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }) as never)

    renderPage()
    await screen.findByRole('checkbox', { name: 'demo' })
    await user.type(screen.getByRole('textbox', { name: '实例代码' }), 'new-browser')
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'New browser')
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大待处理数' }), '4')
    await user.click(screen.getByRole('checkbox', { name: 'demo' }))
    await user.click(screen.getByRole('button', { name: '创建实例' }))

    expect(apiClient.post).toHaveBeenCalledWith('/instances', {
      code: 'new-browser', displayName: 'New browser', websites: ['demo'], maxPending: 4,
    })
    expect(screen.getByRole('button', { name: '正在保存…' })).toBeDisabled()

    resolveCreate?.(stoppedInstance)
    await waitFor(() => expect(screen.getByRole('button', { name: '创建实例' })).toBeEnabled())
  })

  it('calls the exact lifecycle route and requires confirmation before deleting', async () => {
    // This asserts M6 lifecycle routing and makes destructive profile removal opt-in.
    const user = userEvent.setup()
    mockCatalogAndInstances([stoppedInstance])
    vi.mocked(apiClient.post).mockResolvedValue(stoppedInstance)
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)

    renderPage()
    await screen.findByRole('heading', { name: 'Alpha browser' })
    await user.click(screen.getByRole('button', { name: '启动' }))
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/instances/7/start'))

    await user.click(screen.getByRole('button', { name: '删除' }))
    expect(apiClient.delete).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toHaveTextContent('Profile 和浏览器登录状态')
    await user.click(screen.getByRole('button', { name: '删除实例' }))
    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith('/instances/7'))
  })
})
