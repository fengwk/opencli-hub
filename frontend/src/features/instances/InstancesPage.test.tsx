import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
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

const instanceId = '2c6eefbd-a8cf-44fb-8016-14d6886c2557'

const stoppedInstance: HubInstance = {
  id: instanceId, code: 'alpha', displayName: 'Alpha browser', contextId: null, state: 'STOPPED',
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
    expect(screen.getByText('等待注册')).toBeInTheDocument()
    expect(screen.getByText(/活跃 0 · 待处理 0\/3/)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('最近错误：last launch failed')
    expect(screen.getByRole('link', { name: '详情与控制台' })).toHaveAttribute('href', `/instances/${instanceId}`)
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

  it('opens creation on demand, sends the DTO, and closes the panel after creation completes', async () => {
    // The closed-first panel keeps the fleet view compact; the unresolved POST proves its submit cannot be repeated.
    const user = userEvent.setup()
    let resolveCreate: ((value: HubInstance) => void) | undefined
    mockCatalogAndInstances([])
    vi.mocked(apiClient.post).mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }) as never)

    renderPage()
    expect(screen.queryByRole('dialog', { name: '创建浏览器实例' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建实例' }))
    expect(screen.getByRole('dialog', { name: '创建浏览器实例' })).toBeInTheDocument()
    await screen.findByRole('checkbox', { name: 'demo' })
    await user.type(screen.getByRole('textbox', { name: '实例代码' }), 'new-browser')
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'New browser')
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大待处理数' }), '4')
    await user.click(screen.getByRole('checkbox', { name: 'demo' }))
    await user.click(within(screen.getByRole('dialog', { name: '创建浏览器实例' })).getByRole('button', { name: '创建实例' }))

    expect(apiClient.post).toHaveBeenCalledWith('/instances', {
      code: 'new-browser', displayName: 'New browser', websites: ['demo'], maxPending: 4,
    })
    expect(screen.getByRole('button', { name: '正在保存…' })).toBeDisabled()

    resolveCreate?.(stoppedInstance)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '创建浏览器实例' })).not.toBeInTheDocument())
  })

  it('validates instance fields against the backend contract before creating', async () => {
    // Client bounds mirror HubInstanceValidator so invalid codes, empty sites, and queue limits never reach the API.
    const user = userEvent.setup()
    mockCatalogAndInstances([])

    renderPage()
    await user.click(screen.getByRole('button', { name: '创建实例' }))
    const dialog = screen.getByRole('dialog', { name: '创建浏览器实例' })
    const codeInput = within(dialog).getByRole('textbox', { name: '实例代码' })
    const displayNameInput = within(dialog).getByRole('textbox', { name: '显示名称' })
    const pendingInput = within(dialog).getByRole('spinbutton', { name: '最大待处理数' })
    const website = await within(dialog).findByRole('checkbox', { name: 'demo' })
    const submit = within(dialog).getByRole('button', { name: '创建实例' })

    await user.type(codeInput, 'Invalid_Code')
    await user.type(displayNameInput, 'Browser')
    await user.click(website)
    await user.click(submit)
    expect(within(dialog).getByRole('alert')).toHaveTextContent('实例代码须为')

    await user.clear(codeInput)
    await user.type(codeInput, 'valid-code')
    await user.click(website)
    await user.click(submit)
    expect(within(dialog).getByRole('alert')).toHaveTextContent('至少选择一个')

    await user.click(website)
    await user.clear(pendingInput)
    await user.type(pendingInput, '51')
    await user.click(submit)
    expect(within(dialog).getByRole('alert')).toHaveTextContent('1 到 50')
    expect(apiClient.post).not.toHaveBeenCalled()
  })

  it('contains keyboard focus in the creation drawer and restores it when Escape closes the drawer', async () => {
    // Modal focus and scroll containment keep keyboard users out of the obscured fleet page.
    const user = userEvent.setup()
    mockCatalogAndInstances([])

    renderPage()
    const trigger = screen.getByRole('button', { name: '创建实例' })
    await user.click(trigger)

    const codeInput = await screen.findByRole('textbox', { name: '实例代码' })
    expect(codeInput).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')

    screen.getByRole('button', { name: '取消' }).focus()
    await user.tab()
    expect(screen.getByRole('button', { name: '关闭创建实例面板' })).toHaveFocus()

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog', { name: '创建浏览器实例' })).not.toBeInTheDocument()
    expect(document.body.style.overflow).toBe('')
    expect(trigger).toHaveFocus()
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
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith(`/instances/${instanceId}/start`))

    await user.click(screen.getByRole('button', { name: '删除' }))
    expect(apiClient.delete).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toHaveTextContent('Profile 和浏览器登录状态')
    await user.click(screen.getByRole('button', { name: '删除实例' }))
    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith(`/instances/${instanceId}`))
  })
})
