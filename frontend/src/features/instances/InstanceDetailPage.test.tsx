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
  websites: ['demo'], maxPending: 2, maxConcurrency: 1, priority: 0, proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080', lastErrorMessage: null, stateChangedAt: null,
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
    expect(screen.getByText(/活跃 0\/1 · 待处理 0\/2/)).toBeInTheDocument()
    expect(screen.getByText('自定义 · socks5://proxy.example.com:1080')).toBeInTheDocument()
    expect(screen.getAllByText('是')).toHaveLength(4)
    expect(screen.getByRole('link', { name: '查看日志' })).toHaveAttribute('href', `/logs?instanceId=${instanceId}`)
    expect(apiClient.get).toHaveBeenCalledWith(`/instances/${instanceId}`)
    expect(apiClient.get).toHaveBeenCalledWith(`/instances/${instanceId}/vnc/status`)
  })

  it('defaults legacy instances without proxy, concurrency, and queue fields to safe values and submits them explicitly on save', async () => {
    // Older Hub responses omit maxConcurrency and maxPending; rendering and subsequent edit submits must supply 1 and 5.
    const user = userEvent.setup()
    const legacyInstance = { ...instance, proxyMode: undefined, proxyServer: undefined, maxConcurrency: undefined, maxPending: undefined } as unknown as HubInstance
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : url === '/opencli/commands' ? [{
        commandKey: 'demo/search', site: 'demo', name: 'search', aliases: null, description: null,
        access: 'READ', browser: true, args: null, siteSession: null, defaultWindowMode: null,
        blacklisted: false, blacklistReason: null, outputRule: null,
      }] : legacyInstance,
    ) as never)
    vi.mocked(apiClient.put).mockResolvedValue(instance)

    renderPage()

    expect(await screen.findByText('继承全局')).toBeInTheDocument()
    expect(screen.getByText(/活跃 0\/1 · 待处理 0\/5/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '编辑' }))
    await screen.findByRole('checkbox', { name: 'demo' })
    expect(screen.getByRole('spinbutton', { name: '最大并发数' })).toHaveValue(1)
    expect(screen.getByRole('spinbutton', { name: '最大待处理数' })).toHaveValue(5)
    await user.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith(`/instances/${instanceId}`, {
      code: 'beta', displayName: 'Beta browser', websites: ['demo'], maxConcurrency: 1, maxPending: 5, priority: 0,
      proxyMode: 'INHERIT', proxyServer: null,
    }))
  })

  it('saves edits through the exact update route and refreshes the instance', async () => {
    // Editing checks that all editable properties, including maxConcurrency and maxPending, are sent in one PUT payload.
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
      code: 'beta', displayName: 'Updated browser', websites: ['demo'], maxConcurrency: 1, maxPending: 2, priority: 0,
      proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080',
    }))
  })

  it('allows editing maxPending=0 and custom maxConcurrency to disable queuing', async () => {
    // Setting maxPending=0 in edit mode must submit properly to disallow queuing on that instance.
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
    await user.clear(screen.getByRole('spinbutton', { name: '最大并发数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大并发数' }), '3')
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大待处理数' }), '0')
    await user.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith(`/instances/${instanceId}`, {
      code: 'beta', displayName: 'Beta browser', websites: ['demo'], maxConcurrency: 3, maxPending: 0, priority: 0,
      proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080',
    }))
  })

  it('validates concurrency and queue limits when editing an instance', async () => {
    // Range bounds 1..4 for maxConcurrency and 0..50 for maxPending must prevent invalid edit submits.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : url === '/opencli/commands' ? [{
        commandKey: 'demo/search', site: 'demo', name: 'search', aliases: null, description: null,
        access: 'READ', browser: true, args: null, siteSession: null, defaultWindowMode: null,
        blacklisted: false, blacklistReason: null, outputRule: null,
      }] : instance,
    ) as never)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    await user.click(screen.getByRole('button', { name: '编辑' }))
    await screen.findByRole('checkbox', { name: 'demo' })

    const concurrencyInput = screen.getByRole('spinbutton', { name: '最大并发数' })
    const pendingInput = screen.getByRole('spinbutton', { name: '最大待处理数' })
    const submit = screen.getByRole('button', { name: '保存更改' })

    await user.clear(concurrencyInput)
    await user.type(concurrencyInput, '0')
    await user.click(submit)
    expect(screen.getByRole('alert')).toHaveTextContent('1 到 4')

    await user.clear(concurrencyInput)
    await user.type(concurrencyInput, '2')
    await user.clear(pendingInput)
    await user.type(pendingInput, '55')
    await user.click(submit)
    expect(screen.getByRole('alert')).toHaveTextContent('0 到 50')

    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('confirms before binding the current VNC tab and shows success', async () => {
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : instance,
    ) as never)
    vi.mocked(apiClient.post).mockResolvedValue(undefined)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    const bindButton = screen.getByRole('button', { name: '绑定当前 VNC 标签页' })
    expect(bindButton).toBeEnabled()
    await user.click(bindButton)

    expect(screen.getByText(/先在 VNC 中选中目标 ChatGPT tab/)).toBeInTheDocument()
    expect(apiClient.post).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '绑定当前标签页' }))

    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith(
      `/instances/${instanceId}/chatgpt-agent/bind-active-tab`,
    ))
    expect(await screen.findByText('已绑定当前 VNC 标签页。')).toBeInTheDocument()
  })

  it('disables binding when the instance is unavailable', async () => {
    const unavailable = { ...instance, state: 'STOPPED' as const, runtime: null }
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : unavailable,
    ) as never)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    expect(screen.getByRole('button', { name: '绑定当前 VNC 标签页' })).toBeDisabled()
  })

  it('disables binding while work is active', async () => {
    const busy = { ...instance, runtime: { ...instance.runtime!, activeCount: 1 } }
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : busy,
    ) as never)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    expect(screen.getByRole('button', { name: '绑定当前 VNC 标签页' })).toBeDisabled()
  })

  it('shows bind failures in the existing page error area', async () => {
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status` ? vncStatus : instance,
    ) as never)
    vi.mocked(apiClient.post).mockRejectedValue(new Error('目标 tab 不可用'))

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    await user.click(screen.getByRole('button', { name: '绑定当前 VNC 标签页' }))
    await user.click(screen.getByRole('button', { name: '绑定当前标签页' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('目标 tab 不可用')
  })
})
