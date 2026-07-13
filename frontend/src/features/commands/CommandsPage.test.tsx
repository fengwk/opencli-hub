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

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CommandsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => vi.clearAllMocks())

describe('CommandsPage', () => {
  it('shows loading then command metadata and argument details from the catalog', async () => {
    // A delayed catalog response proves both the loading state and rendered command contract.
    let resolveCatalog: ((value: HubCommand[]) => void) | undefined
    vi.mocked(apiClient.get).mockImplementationOnce(() => new Promise((resolve) => {
      resolveCatalog = resolve
    }))

    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载命令目录')

    resolveCatalog?.([command])
    expect(await screen.findByRole('heading', { name: 'demo/search' })).toBeInTheDocument()
    expect(screen.getByText('--output')).toBeInTheDocument()
    expect(screen.getByText('Search the demo site')).toBeInTheDocument()
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
    await user.click(screen.getByRole('button', { name: '编辑输出规则' }))
    await user.selectOptions(screen.getByRole('combobox', { name: '输出目标' }), 'FILE')
    await user.type(screen.getByRole('textbox', { name: /文件名/ }), 'nested/result.json')
    await user.click(screen.getByRole('button', { name: '保存输出规则' }))

    expect(screen.getByRole('alert')).toHaveTextContent('文件名只能包含')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('sends the optional blacklist reason and saves a validated FILE output rule', async () => {
    // These mutations assert the exact M6 route and JSON bodies used by the two policy editors.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue([command])
    vi.mocked(apiClient.put).mockResolvedValue(command)

    renderPage()
    await screen.findByRole('heading', { name: 'demo/search' })

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
