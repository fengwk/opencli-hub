import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ResourcesPage } from '@/features/resources/ResourcesPage'
import type { ResourceDateSummary, ResourceItem } from '@/features/resources/types'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  apiBaseUrl: '/api',
}))

const dateSummary: ResourceDateSummary = {
  date: '2026-07-13',
  groupCount: 1,
  fileCount: 1,
  totalSize: 1536,
}

const resource: ResourceItem = {
  date: '2026-07-13',
  group: 'upload-100',
  relativePath: 'nested/photo one.png',
  resourcePath: '/resources/2026-07-13/upload-100/nested/photo one.png',
  fileName: 'photo one.png',
  source: 'UPLOAD',
  mimeType: 'image/png',
  size: 1536,
  modifiedAt: '2026-07-13T10:00:00',
  contentUrl: '/api/resources/unused',
  downloadUrl: '/api/resources/unused',
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ResourcesPage />
    </QueryClientProvider>,
  )
}

function mockDatesAndResources() {
  vi.mocked(apiClient.get).mockImplementation((url) => {
    if (url === '/resources/dates') return Promise.resolve([dateSummary])
    if (url === '/resources') return Promise.resolve([resource])
    return Promise.reject(new Error(`unexpected GET ${url}`))
  })
}

afterEach(() => vi.clearAllMocks())

describe('ResourcesPage', () => {
  it('loads date summaries and the selected day resources', async () => {
    // Date and file assertions prove the page performs the two-stage resource browse flow.
    mockDatesAndResources()

    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载资源日期')

    expect(await screen.findByRole('button', { name: /^2026-07-13/ })).toBeInTheDocument()
    expect(await screen.findByText('nested/photo one.png')).toBeInTheDocument()
    expect(screen.getByText('UPLOAD', { selector: '.badge' })).toBeInTheDocument()
    expect(apiClient.get).toHaveBeenCalledWith('/resources', {
      params: {
        date: '2026-07-13',
        sort: 'MODIFIED_DESC',
        page: 0,
        pageSize: 100,
      },
    })
  })

  it('shows an empty state without starting a disabled day query when no dates exist', async () => {
    // An empty date response must not leave the disabled resource query in a permanent loading state.
    vi.mocked(apiClient.get).mockResolvedValueOnce([])

    renderPage()

    expect(await screen.findByText('暂无资源')).toBeInTheDocument()
    expect(screen.queryByText('正在加载当天资源…')).not.toBeInTheDocument()
    expect(apiClient.get).toHaveBeenCalledTimes(1)
  })

  it('shows an API error when date summaries cannot load', async () => {
    // The browse error remains actionable instead of hiding the failed API request.
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('dates unavailable'))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('dates unavailable')
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })

  it('uploads all selected files to the selected UTC date', async () => {
    // FormData inspection proves multi-file uploads use the M6 multipart field and date query.
    const user = userEvent.setup()
    mockDatesAndResources()
    vi.mocked(apiClient.post).mockResolvedValue({
      date: dateSummary.date,
      group: resource.group,
      items: [resource],
    })

    renderPage()
    await screen.findByText('nested/photo one.png')
    const fileInput = screen.getByLabelText('选择文件')
    const first = new File(['first'], 'first.txt', { type: 'text/plain' })
    const second = new File(['second'], 'second.txt', { type: 'text/plain' })
    await user.upload(fileInput, [first, second])
    await user.click(screen.getByRole('button', { name: '上传文件' }))

    await waitFor(() => expect(apiClient.post).toHaveBeenCalledTimes(1))
    const [url, body] = vi.mocked(apiClient.post).mock.calls[0]
    expect(url).toBe('/resources/uploads?date=2026-07-13')
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).getAll('files')).toEqual([first, second])
    await waitFor(() => expect((fileInput as HTMLInputElement).files).toHaveLength(0))
  })

  it('uses encoded helper URLs for image preview and download', async () => {
    // The URL assertions prevent preview/download paths from bypassing the safe shared encoder.
    const user = userEvent.setup()
    mockDatesAndResources()

    renderPage()
    await screen.findByText('nested/photo one.png')

    expect(screen.getByRole('link', { name: '下载' })).toHaveAttribute(
      'href',
      '/api/resources/2026-07-13/upload-100/nested/photo%20one.png',
    )
    const previewButton = screen.getByRole('button', { name: '预览' })
    await user.click(previewButton)
    expect(screen.getByRole('img', { name: 'photo one.png' })).toHaveAttribute(
      'src',
      '/api/resources/2026-07-13/upload-100/nested/photo%20one.png?inline=true',
    )
    expect(screen.getByRole('button', { name: '关闭' })).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('img', { name: 'photo one.png' })).not.toBeInTheDocument()
    expect(previewButton).toHaveFocus()
  })

  it('requires confirmation before deleting a file, group, or date', async () => {
    // Each deletion route is only invoked after the shared confirmation dialog is accepted.
    const user = userEvent.setup()
    mockDatesAndResources()
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)

    renderPage()
    await screen.findByText('nested/photo one.png')

    await user.click(screen.getByRole('button', { name: '删除文件' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('photo one.png')
    expect(apiClient.delete).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '删除' }))
    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith(
      '/resources/2026-07-13/upload-100/nested/photo%20one.png',
    ))

    await user.click(screen.getByRole('button', { name: '删除资源组' }))
    await user.click(screen.getByRole('button', { name: '删除' }))
    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith('/resources/2026-07-13/upload-100'))

    await user.click(screen.getByRole('button', { name: '删除日期 2026-07-13' }))
    await user.click(screen.getByRole('button', { name: '删除' }))
    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith('/resources/2026-07-13'))
  })
})
