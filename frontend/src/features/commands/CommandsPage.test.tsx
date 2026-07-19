import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CommandsPage } from '@/features/commands/CommandsPage'
import type { HubCommand } from '@/features/commands/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

const command: HubCommand = {
  commandKey: 'demo/search',
  site: 'demo',
  name: 'search',
  aliases: ['find'],
  description: 'Search the demo site',
  access: 'READ',
  browser: true,
  args: [{
    name: 'output',
    type: 'string',
    required: false,
    valueRequired: true,
    positional: false,
    choices: null,
    defaultValue: null,
    help: 'Output destination',
  }],
  siteSession: 'EPHEMERAL',
  defaultWindowMode: 'new-tab',
  blacklisted: false,
  blacklistReason: null,
  outputRule: null,
}

const secondCommand: HubCommand = {
  ...command,
  commandKey: 'github/issues',
  site: 'github',
  name: 'issues',
  aliases: null,
  description: 'Browse repository issues',
  siteSession: 'PERSISTENT',
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CommandsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  Reflect.deleteProperty(navigator, 'clipboard')
  vi.clearAllMocks()
})

describe('CommandsPage', () => {
  it('keeps command policy details unmounted until the compact card is expanded', async () => {
    // A delayed catalog response proves the default card remains compact before explicitly mounting details and policy controls.
    const user = userEvent.setup()
    let resolveCatalog: ((value: HubCommand[]) => void) | undefined
    vi.mocked(apiClient.get).mockImplementationOnce(() => new Promise((resolve) => {
      resolveCatalog = resolve
    }))

    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载命令目录')

    resolveCatalog?.([command])
    expect(await screen.findByRole('heading', { name: 'demo/search' })).toBeInTheDocument()
    expect(screen.getByText('Search the demo site')).toBeInTheDocument()
    const expand = screen.getByRole('button', { name: '查看详情与策略' })
    expect(expand).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('--output')).not.toBeInTheDocument()

    await user.click(expand)
    expect(screen.getByRole('button', { name: '收起详情与策略' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('--output')).toBeInTheDocument()
  })

  it('copies a Hub-controlled curl template from expanded command details', async () => {
    // The template must use the public controlled endpoint instead of exposing a direct CLI invocation.
    const writeText = vi.fn().mockResolvedValue(undefined)
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    vi.mocked(apiClient.get).mockResolvedValue([command])

    renderPage()
    await screen.findByRole('heading', { name: 'demo/search' })
    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))
    await user.click(screen.getByRole('button', { name: '复制 curl 模板' }))

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('"<HUB_URL>/api/opencli/execute"'))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('"argv": [\n    "demo",\n    "search"'))
    expect(screen.getByText('curl 模板已复制到本机剪贴板。')).toBeInTheDocument()
  })

  it('keeps the full website catalogue while filtering commands locally', async () => {
    // Loading once preserves every website option and avoids a second request when operators switch filters.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue([command, secondCommand])

    renderPage()
    await screen.findByRole('heading', { name: 'demo/search' })
    expect(screen.getByRole('heading', { name: 'github/issues' })).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: '网站' }), 'demo')

    expect(screen.getByRole('heading', { name: 'demo/search' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'github/issues' })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'github' })).toBeInTheDocument()
    expect(apiClient.get).toHaveBeenCalledTimes(1)
  })

  it('surfaces catalog load errors with a retry action', async () => {
    // A rejected API promise must remain visible instead of leaving a blank catalog.
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('catalog unavailable'))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('catalog unavailable')
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })

  it('rejects unsafe output filenames before calling the policy API', async () => {
    // Client validation blocks a path-like file name that the M6 output-rule contract forbids.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue([command])

    renderPage()
    await screen.findByRole('heading', { name: 'demo/search' })
    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))
    await user.click(screen.getByRole('button', { name: '编辑输出规则' }))
    await user.selectOptions(screen.getByRole('combobox', { name: '输出目标' }), 'FILE')
    await user.type(screen.getByRole('textbox', { name: /文件名/ }), 'nested/result.json')
    await user.click(screen.getByRole('button', { name: '保存输出规则' }))

    expect(screen.getByRole('alert')).toHaveTextContent('文件名只能包含')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('keeps the output-rule editor open when the policy request fails', async () => {
    // Preserving the editor prevents an API failure from discarding the operator's pending configuration.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue([command])
    vi.mocked(apiClient.put).mockRejectedValue(new Error('policy unavailable'))

    renderPage()
    await screen.findByRole('heading', { name: 'demo/search' })
    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))
    await user.click(screen.getByRole('button', { name: '编辑输出规则' }))
    await user.click(screen.getByRole('button', { name: '保存输出规则' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('policy unavailable')
    expect(screen.getByRole('button', { name: '保存输出规则' })).toBeInTheDocument()
  })

  it('sends the optional blacklist reason and saves a validated FILE output rule', async () => {
    // These mutations assert the exact M6 route and JSON bodies used by the two policy editors.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue([command])
    vi.mocked(apiClient.put).mockResolvedValue(command)

    renderPage()
    await screen.findByRole('heading', { name: 'demo/search' })
    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))

    await user.type(screen.getByRole('textbox', { name: /黑名单原因/ }), 'maintenance')
    await user.click(screen.getByRole('button', { name: '加入黑名单' }))
    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith(
      '/opencli/commands/demo/search/blacklist',
      { reason: 'maintenance' },
    ))

    await user.click(screen.getByRole('button', { name: '编辑输出规则' }))
    await user.selectOptions(screen.getByRole('combobox', { name: '输出目标' }), 'FILE')
    await user.type(screen.getByRole('textbox', { name: /文件名/ }), 'result.json')
    await user.click(screen.getByRole('button', { name: '保存输出规则' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith(
      '/opencli/commands/demo/search/output-rule',
      { argumentName: 'output', targetType: 'FILE', fileName: 'result.json' },
    ))
  })
})
