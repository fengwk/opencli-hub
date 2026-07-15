import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SettingsPage } from '@/features/settings/SettingsPage'
import type { HubSettings } from '@/features/settings/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const directSettings: HubSettings = { proxyMode: 'DIRECT', proxyServer: null }
const customSettings: HubSettings = { proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080' }

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <SettingsPage />
    </QueryClientProvider>,
  )
}

afterEach(() => vi.clearAllMocks())

describe('SettingsPage', () => {
  it('shows a loading state, then renders the global proxy form and effect scope', async () => {
    // Deferring GET verifies operators receive a labeled loading state before settings are available.
    let resolveSettings: ((value: HubSettings) => void) | undefined
    vi.mocked(apiClient.get).mockImplementation(() => new Promise((resolve) => { resolveSettings = resolve }) as never)

    renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('正在加载系统设置')
    resolveSettings?.(directSettings)
    expect(await screen.findByRole('combobox', { name: '全局代理模式' })).toHaveValue('DIRECT')
    expect(screen.getByText('代理只控制浏览器访问网站的流量。')).toBeInTheDocument()
    expect(screen.getByText('容器 localhost 上的 OpenCLI 服务不会经过代理。')).toBeInTheDocument()
    expect(screen.getByText(/bridge 模式下 127\.0\.0\.1 指向容器自身/)).toBeInTheDocument()
    expect(screen.getByText(/正在运行的实例必须手动重启后才能生效/)).toBeInTheDocument()
    expect(apiClient.get).toHaveBeenCalledWith('/settings')
  })

  it('shows a retryable load error when system settings cannot be fetched', async () => {
    // A failed GET must not leave the settings route blank or expose a stale editable form.
    vi.mocked(apiClient.get).mockRejectedValue(new Error('settings unavailable'))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('settings unavailable')
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })

  it('defaults a legacy response without proxy fields to direct mode', async () => {
    // Pre-proxy Hub responses omit both fields; the safe fallback must never imply a custom proxy.
    vi.mocked(apiClient.get).mockResolvedValue({} as never)

    renderPage()

    expect(await screen.findByRole('combobox', { name: '全局代理模式' })).toHaveValue('DIRECT')
    expect(screen.queryByRole('textbox', { name: '代理服务器' })).not.toBeInTheDocument()
  })

  it('validates custom proxy input and saves the normalized custom settings payload', async () => {
    // The sequence proves credentials are blocked locally and only a contract-compliant server reaches PUT.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue(directSettings)
    vi.mocked(apiClient.put).mockResolvedValue(customSettings)

    renderPage()

    const mode = await screen.findByRole('combobox', { name: '全局代理模式' })
    await user.selectOptions(mode, 'CUSTOM')
    const proxyServer = screen.getByRole('textbox', { name: '代理服务器' })
    await user.type(proxyServer, 'http://user:secret@proxy.example.com:8080')
    await user.click(screen.getByRole('button', { name: '保存设置' }))
    expect(screen.getByRole('alert')).toHaveTextContent('不支持用户名或密码')
    expect(apiClient.put).not.toHaveBeenCalled()

    await user.clear(proxyServer)
    await user.type(proxyServer, '  socks5://proxy.example.com:1080  ')
    await user.click(screen.getByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith('/settings', {
      proxyMode: 'CUSTOM', proxyServer: 'socks5://proxy.example.com:1080',
    }))
    expect(await screen.findByRole('status')).toHaveTextContent('系统设置已保存')
  })

  it('disables the save control while an update is in flight', async () => {
    // A deferred PUT verifies a keyboard or pointer user cannot submit the same settings twice.
    const user = userEvent.setup()
    let resolveUpdate: ((value: HubSettings) => void) | undefined
    vi.mocked(apiClient.get).mockResolvedValue(directSettings)
    vi.mocked(apiClient.put).mockImplementation(() => new Promise((resolve) => { resolveUpdate = resolve }) as never)

    renderPage()

    await screen.findByRole('combobox', { name: '全局代理模式' })
    await user.click(screen.getByRole('button', { name: '保存设置' }))
    expect(screen.getByRole('button', { name: '正在保存…' })).toBeDisabled()

    resolveUpdate?.(directSettings)
    expect(await screen.findByRole('status')).toHaveTextContent('系统设置已保存')
  })

  it('submits null proxyServer for direct mode and surfaces save failures', async () => {
    // Direct mode must clear a stale custom address, and mutation failures remain visible to the operator.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue(customSettings)
    vi.mocked(apiClient.put).mockRejectedValue(new Error('save unavailable'))

    renderPage()

    const mode = await screen.findByRole('combobox', { name: '全局代理模式' })
    await user.selectOptions(mode, 'DIRECT')
    await user.click(screen.getByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(apiClient.put).toHaveBeenCalledWith('/settings', {
      proxyMode: 'DIRECT', proxyServer: null,
    }))
    expect(await screen.findByRole('alert')).toHaveTextContent('save unavailable')
  })
})
