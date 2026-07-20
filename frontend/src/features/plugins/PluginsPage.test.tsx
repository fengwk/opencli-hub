import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PluginsPage } from '@/features/plugins/PluginsPage'
import type { HubPluginSource } from '@/features/plugins/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

const source: HubPluginSource = {
  id: 'source-1',
  name: 'my-opencli',
  source: 'https://github.com/fengwk/my-opencli',
  desiredPlugins: ['chatgpt-agent'],
  enabled: true,
  lastStatus: 'IDLE',
  lastError: null,
  lastSyncedAt: null,
  lastResult: null,
  version: '0',
}

const defaultSource: HubPluginSource = {
  ...source,
  id: 'source-2',
  name: 'default-collection',
  desiredPlugins: [],
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <PluginsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => vi.clearAllMocks())

describe('PluginsPage', () => {
  it('distinguishes an unsynchronized source from an empty installed plugin list', async () => {
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValueOnce([source, defaultSource]).mockResolvedValueOnce([])

    renderPage()

    expect(await screen.findByRole('heading', { name: 'my-opencli' })).toBeInTheDocument()
    expect(screen.getAllByText('尚未同步')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '安装/更新已选子插件' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '安装默认集合' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '更新已安装' })).toHaveLength(2)
    expect(screen.getByText('尚未安装插件')).toBeInTheDocument()
    expect(screen.queryByText('自动更新标记')).not.toBeInTheDocument()
    expect(screen.queryByText(/标记为可自动更新/)).not.toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: '编辑' })[0])
    expect(screen.getByRole('checkbox', { name: '启用插件源' })).toBeChecked()
  })

  it('calls update-installed for the dedicated refresh button', async () => {
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValueOnce([defaultSource]).mockResolvedValueOnce([])
    vi.mocked(apiClient.post).mockResolvedValue(defaultSource)

    renderPage()
    expect(await screen.findByRole('button', { name: '更新已安装' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '更新已安装' }))
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith(
      '/plugins/sources/source-2/update-installed',
      undefined,
      { timeout: 320_000 },
    ))
  })

  it('saves source configuration without an automatic-update field', async () => {
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue([])
    vi.mocked(apiClient.post).mockResolvedValue(source)

    renderPage()
    await screen.findByText('还没有插件源')
    await user.click(screen.getByRole('button', { name: '新增插件源' }))
    await user.type(screen.getByRole('textbox', { name: '名称' }), 'my-opencli')
    await user.type(screen.getByRole('textbox', { name: 'OpenCLI source' }), 'https://github.com/fengwk/my-opencli')
    await user.type(screen.getByRole('textbox', { name: /子插件名/ }), 'chatgpt-agent')
    await user.click(screen.getByRole('button', { name: '保存配置' }))

    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/plugins/sources', {
      name: 'my-opencli',
      source: 'https://github.com/fengwk/my-opencli',
      desiredPlugins: ['chatgpt-agent'],
      enabled: true,
    }))
  })
})
