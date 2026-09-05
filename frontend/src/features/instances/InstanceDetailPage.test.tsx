import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InstanceDetailPage } from '@/features/instances/InstanceDetailPage'
import { bindInstanceActiveTab } from '@/features/instances/instances-api'
import type { HubInstance, HubInstanceVncStatus } from '@/features/instances/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const instanceId = '343020517415976960'
const instance: HubInstance = {
  id: instanceId, code: 'beta', displayName: 'Beta browser', contextId: 'ctx-42', state: 'RUNNING',
  websites: ['chatgpt-agent', 'custom-hub', 'ephemeral-site', 'non-browser'], maxPending: 2, priority: 0,
  proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080', lastErrorMessage: null, stateChangedAt: null,
  runtime: { registered: true, displayNumber: 13, vncPort: 5901, activeCount: 0, pendingCount: 0 },
  createTime: null, updateTime: null,
}
const vncStatus: HubInstanceVncStatus = {
  instanceId, instanceAvailable: true, running: true, runtimeAvailable: true, vncAvailable: true,
}
const defaultCommands = [
  {
    commandKey: 'chatgpt-agent/ask', site: 'chatgpt-agent', name: 'ask', aliases: null, description: null,
    access: 'READ', browser: true, args: null, siteSession: 'PERSISTENT', defaultWindowMode: null,
    blacklisted: false, blacklistReason: null, outputRule: null,
  },
  {
    commandKey: 'custom-hub/search', site: 'custom-hub', name: 'search', aliases: null, description: null,
    access: 'READ', browser: true, args: null, siteSession: 'PERSISTENT', defaultWindowMode: null,
    blacklisted: false, blacklistReason: null, outputRule: null,
  },
]

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
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : instance,
    ) as never)

    renderPage()

    expect(await screen.findByRole('heading', { name: 'Beta browser' })).toBeInTheDocument()
    expect(screen.getByText('ctx-42')).toBeInTheDocument()
    expect(screen.getByText('VNC 可用')).toBeInTheDocument()
    expect(screen.getByText('自定义 · socks5://proxy.example.com:1080')).toBeInTheDocument()
    expect(screen.getAllByText('是')).toHaveLength(4)
    expect(screen.getByRole('link', { name: '查看日志' })).toHaveAttribute('href', `/logs?instanceId=${instanceId}`)
    expect(apiClient.get).toHaveBeenCalledWith(`/instances/${instanceId}`)
    expect(apiClient.get).toHaveBeenCalledWith(`/instances/${instanceId}/vnc/status`)
  })

  it('defaults legacy instances without proxy fields to inherit the global setting', async () => {
    // Older Hub responses omit the fields entirely; rendering must retain the safe inherited behavior.
    const legacyInstance = { ...instance, proxyMode: undefined, proxyServer: undefined } as unknown as HubInstance
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : legacyInstance,
    ) as never)

    renderPage()

    expect(await screen.findByText('继承全局')).toBeInTheDocument()
  })

  it('saves edits through the exact update route and refreshes the instance', async () => {
    // Editing checks that all editable M6 properties, including catalog-selected sites, are sent in one PUT payload.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : instance,
    ) as never)
    vi.mocked(apiClient.put).mockResolvedValue(instance)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    await user.click(screen.getByRole('button', { name: '编辑' }))
    await screen.findByRole('checkbox', { name: 'chatgpt-agent' })
    await user.clear(screen.getByRole('textbox', { name: '显示名称' }))
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'Updated browser')
    await user.click(screen.getByRole('button', { name: '保存更改' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith(`/instances/${instanceId}`, {
      code: 'beta', displayName: 'Updated browser', websites: ['chatgpt-agent', 'custom-hub', 'ephemeral-site', 'non-browser'],
      maxPending: 2, priority: 0, proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080',
    }))
  })

  it('derives bindable sites dynamically from catalog filtering by persistent mode and enabled websites', async () => {
    // Verifies that only browser commands with siteSession: 'PERSISTENT' matching instance.websites are listed,
    // while ephemeral, non-browser, and unassigned persistent sites are excluded, and choices are sorted deterministically.
    const catalog = [
      { site: 'custom-hub', name: 'search', browser: true, siteSession: 'PERSISTENT' },
      { site: 'chatgpt-agent', name: 'ask', browser: true, siteSession: 'PERSISTENT' },
      { site: 'chatgpt-agent', name: 'draw', browser: true, siteSession: 'PERSISTENT' }, // duplicate site
      { site: 'ephemeral-site', name: 'scrape', browser: true, siteSession: 'EPHEMERAL' }, // ephemeral -> excluded
      { site: 'non-browser', name: 'ping', browser: false, siteSession: 'PERSISTENT' }, // non-browser -> excluded
      { site: 'unassigned-site', name: 'exec', browser: true, siteSession: 'PERSISTENT' }, // not in instance.websites -> excluded
    ]
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? catalog
          : instance,
    ) as never)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })

    const select = screen.getByRole('combobox', { name: '绑定目标网站' })
    expect(select).toBeEnabled()
    const options = Array.from(select.querySelectorAll('option')).map((opt) => opt.value)
    expect(options).toEqual(['chatgpt-agent', 'custom-hub'])
    expect(select).toHaveValue('chatgpt-agent')
  })

  it('confirms and binds an arbitrary selected third-party persistent site route with dynamic text', async () => {
    // Verifies that selecting an arbitrary third-party site interpolates that site into confirmation,
    // posts to the generalized route /instances/{id}/{site}/bind-active-tab, and shows dynamic success feedback.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : instance,
    ) as never)
    vi.mocked(apiClient.post).mockResolvedValue(undefined)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })

    const select = screen.getByRole('combobox', { name: '绑定目标网站' })
    await user.selectOptions(select, 'custom-hub')
    expect(select).toHaveValue('custom-hub')

    const bindButton = screen.getByRole('button', { name: '绑定当前 VNC 标签页' })
    expect(bindButton).toBeEnabled()
    await user.click(bindButton)

    expect(screen.getByText('绑定 custom-hub 标签页？')).toBeInTheDocument()
    expect(screen.getByText(/请先在 VNC 中选中目标/)).toHaveTextContent('custom-hub')
    expect(apiClient.post).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '确认绑定 custom-hub' }))

    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith(
      `/instances/${instanceId}/custom-hub/bind-active-tab`,
    ))
    expect(await screen.findByText('已将当前 VNC 标签页绑定至 custom-hub。')).toBeInTheDocument()
  })

  it('binds chatgpt-agent route without specialization and updates success feedback', async () => {
    // Verifies chatgpt-agent route POST /instances/{id}/chatgpt-agent/bind-active-tab is preserved naturally.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : instance,
    ) as never)
    vi.mocked(apiClient.post).mockResolvedValue(undefined)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })

    const bindButton = screen.getByRole('button', { name: '绑定当前 VNC 标签页' })
    expect(bindButton).toBeEnabled()
    await user.click(bindButton)

    expect(screen.getByText('绑定 chatgpt-agent 标签页？')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认绑定 chatgpt-agent' }))

    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith(
      `/instances/${instanceId}/chatgpt-agent/bind-active-tab`,
    ))
    expect(await screen.findByText('已将当前 VNC 标签页绑定至 chatgpt-agent。')).toBeInTheDocument()
  })

  it('disables binding when the instance is unavailable or busy', async () => {
    // Verifies bind action is disabled when instance is STOPPED or execution queue has active items.
    const unavailable = { ...instance, state: 'STOPPED' as const, runtime: null }
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : unavailable,
    ) as never)

    const { unmount } = renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    expect(screen.getByRole('combobox', { name: '绑定目标网站' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '绑定当前 VNC 标签页' })).toBeDisabled()
    unmount()

    const busy = { ...instance, runtime: { ...instance.runtime!, activeCount: 1 } }
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : busy,
    ) as never)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    expect(screen.getByRole('combobox', { name: '绑定目标网站' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '绑定当前 VNC 标签页' })).toBeDisabled()
  })

  it('disables binding and reflects status when command catalog fails or has no bindable sites', async () => {
    // Verifies that when commands query fails or instance has no persistent sites matching catalog,
    // the selector and bind action are properly disabled.
    vi.mocked(apiClient.get).mockImplementation((url: string) => {
      if (url === `/instances/${instanceId}/vnc/status`) return Promise.resolve(vncStatus) as never
      if (url === '/opencli/commands') return Promise.reject(new Error('无法读取命令目录'))
      return Promise.resolve(instance) as never
    })

    const { unmount } = renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    const select = screen.getByRole('combobox', { name: '绑定目标网站' })
    expect(select).toBeDisabled()
    expect(screen.getByText('网站加载失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '绑定当前 VNC 标签页' })).toBeDisabled()
    unmount()

    // No persistent sites in catalog matching instance
    const emptyInstance = { ...instance, websites: ['unmatched-site'] }
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : emptyInstance,
    ) as never)

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    const emptySelect = screen.getByRole('combobox', { name: '绑定目标网站' })
    expect(emptySelect).toBeDisabled()
    expect(screen.getByText('无可绑定网站')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '绑定当前 VNC 标签页' })).toBeDisabled()
  })

  it('shows bind failures in the existing page error area and dismisses dialog', async () => {
    // Verifies bind errors dismiss dialog and surface via the unified alert area.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockImplementation((url: string) => Promise.resolve(
      url === `/instances/${instanceId}/vnc/status`
        ? vncStatus
        : url === '/opencli/commands'
          ? defaultCommands
          : instance,
    ) as never)
    vi.mocked(apiClient.post).mockRejectedValue(new Error('目标 tab 不可用'))

    renderPage()
    await screen.findByRole('heading', { name: 'Beta browser' })
    await user.click(screen.getByRole('button', { name: '绑定当前 VNC 标签页' }))
    await user.click(screen.getByRole('button', { name: '确认绑定 chatgpt-agent' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('目标 tab 不可用')
    expect(screen.queryByText('绑定 chatgpt-agent 标签页？')).not.toBeInTheDocument()
  })
})

describe('bindInstanceActiveTab API helper', () => {
  it('encodes arbitrary site names and routes to POST /instances/{id}/{site}/bind-active-tab', async () => {
    // Verifies API client contract: encodes any passed site parameter into the URL path.
    vi.mocked(apiClient.post).mockResolvedValue(undefined)

    await bindInstanceActiveTab(instanceId, 'custom-agent')
    expect(apiClient.post).toHaveBeenCalledWith(`/instances/${instanceId}/custom-agent/bind-active-tab`)

    await bindInstanceActiveTab(instanceId, 'site with spaces')
    expect(apiClient.post).toHaveBeenCalledWith(`/instances/${instanceId}/site%20with%20spaces/bind-active-tab`)
  })
})
