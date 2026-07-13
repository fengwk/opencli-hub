import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LogsPage } from '@/features/logs/LogsPage'
import { getInstanceLogs } from '@/features/logs/logs-api'
import type { HubLogContent, InstanceLogSource } from '@/features/logs/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiBaseUrl: '/api',
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

const log: HubLogContent = {
  source: 'SYSTEM',
  instanceId: null,
  content: '[INFO] first line\n[ERROR] second line',
  truncated: false,
  fileSize: 31,
  modifiedAt: '2026-07-13T10:00:00',
}

function renderPage(initialEntry = '/logs') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <LogsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('LogsPage', () => {
  it('loads system logs with only the bounded line-count parameter', async () => {
    // System mode must use its dedicated endpoint rather than leaking an instance source into the request.
    vi.mocked(apiClient.get).mockResolvedValue(log)

    renderPage()

    await screen.findByLabelText('日志内容')
    expect(apiClient.get).toHaveBeenCalledWith('/logs/system', { params: { lines: 500 } })
  })

  it('initializes Instance mode from the query parameter and sends the selected fixed source and line count', async () => {
    // The URL shortcut and form submission together prove that only a positive ID and dropdown source reach the instance API.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue({ ...log, source: 'CHROME', instanceId: 17 })

    renderPage('/logs?instanceId=17')

    await screen.findByLabelText('日志内容')
    expect(apiClient.get).toHaveBeenLastCalledWith('/instances/17/logs', {
      params: { source: 'CHROME', lines: 500 },
    })

    await user.selectOptions(screen.getByRole('combobox', { name: '进程来源' }), 'X11VNC')
    await user.clear(screen.getByRole('textbox', { name: '行数' }))
    await user.type(screen.getByRole('textbox', { name: '行数' }), '1234')
    await user.click(screen.getByRole('button', { name: '加载日志' }))

    await waitFor(() => expect(apiClient.get).toHaveBeenLastCalledWith('/instances/17/logs', {
      params: { source: 'X11VNC', lines: 1234 },
    }))
  })

  it('filters levels and keywords in the browser while preserving the server line order', async () => {
    // Exact preformatted output demonstrates ordered, case-insensitive client filtering without a second server request.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue({
      ...log,
      content: '[INFO] first Needle\n[ERROR] second needle\n[WARN] third outside',
    })

    renderPage()

    expect((await screen.findByLabelText('日志内容')).textContent).toBe('[INFO] first Needle\n[ERROR] second needle\n[WARN] third outside')
    await user.type(screen.getByRole('textbox', { name: '关键词' }), 'NEEDLE')
    expect(screen.getByLabelText('日志内容').textContent).toBe('[INFO] first Needle\n[ERROR] second needle')

    await user.selectOptions(screen.getByRole('combobox', { name: '级别' }), 'ERROR')
    expect(screen.getByLabelText('日志内容').textContent).toBe('[ERROR] second needle')
    expect(apiClient.get).toHaveBeenCalledTimes(1)
  })

  it('manually refetches the current log request', async () => {
    // Refresh must rerun the existing request, not require an incidental filter or configuration change.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue(log)

    renderPage()
    await screen.findByLabelText('日志内容')
    await user.click(screen.getByRole('button', { name: '手动刷新' }))

    await waitFor(() => expect(apiClient.get).toHaveBeenCalledTimes(2))
  })

  it('refetches exactly after the explicit five-second auto-refresh interval', async () => {
    // Fake time isolates the polling cadence and proves the toggle opts into React Query refetching.
    vi.mocked(apiClient.get).mockResolvedValue(log)

    renderPage()
    await screen.findByLabelText('日志内容')
    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('checkbox', { name: '每 5 秒自动刷新' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })

    expect(apiClient.get).toHaveBeenCalledTimes(2)
  })

  it('builds the raw download URL from the active, validated instance request', async () => {
    // The anchor URL proves downloads use the selected fixed source instead of accepting an arbitrary path or source.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue({ ...log, source: 'X11VNC', instanceId: 8 })

    renderPage('/logs?instanceId=8')
    await screen.findByLabelText('日志内容')
    await user.selectOptions(screen.getByRole('combobox', { name: '进程来源' }), 'X11VNC')
    await user.click(screen.getByRole('button', { name: '加载日志' }))
    await waitFor(() => expect(apiClient.get).toHaveBeenLastCalledWith('/instances/8/logs', {
      params: { source: 'X11VNC', lines: 500 },
    }))

    expect(screen.getByRole('link', { name: '下载当前日志' })).toHaveAttribute(
      'href',
      '/api/instances/8/logs/download?source=X11VNC',
    )
  })

  it('rejects invalid Instance IDs and line counts without issuing a request', async () => {
    // UI validation and API validation together prevent malformed IDs, out-of-range lines, and arbitrary sources from forming paths.
    const user = userEvent.setup()
    vi.mocked(apiClient.get).mockResolvedValue(log)

    renderPage()
    await screen.findByLabelText('日志内容')
    await user.selectOptions(screen.getByRole('combobox', { name: '日志模式' }), 'INSTANCE')
    await user.type(screen.getByRole('textbox', { name: 'Instance ID' }), '0')
    await user.click(screen.getByRole('button', { name: '加载日志' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Instance ID 必须是正整数。')

    await user.clear(screen.getByRole('textbox', { name: 'Instance ID' }))
    await user.type(screen.getByRole('textbox', { name: 'Instance ID' }), '9')
    await user.clear(screen.getByRole('textbox', { name: '行数' }))
    await user.type(screen.getByRole('textbox', { name: '行数' }), '5001')
    await user.click(screen.getByRole('button', { name: '加载日志' }))
    expect(screen.getByRole('alert')).toHaveTextContent('行数必须是 1 到 5000 之间的整数。')
    expect(apiClient.get).toHaveBeenCalledTimes(1)
    expect(() => getInstanceLogs(9, 'UNSAFE' as InstanceLogSource, 500)).toThrow('不支持的日志来源。')
  })
})
