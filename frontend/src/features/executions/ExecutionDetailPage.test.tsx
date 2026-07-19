import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ExecutionDetailPage } from '@/features/executions/ExecutionDetailPage'
import type { HubExecution } from '@/features/executions/types'
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

const executionId = 'f90ce19f-38e7-4ba8-9e6b-e1a7df60fd86'
const instanceId = '8babcbf2-5850-47ca-9f6e-bd77a0a45210'
const resourceGroup = `execution-${executionId}`

const execution: HubExecution = {
  id: executionId,
  instanceId,
  instanceCode: 'persistent-uuid',
  commandKey: 'demo/profile',
  site: 'demo',
  siteSession: 'PERSISTENT',
  reuseInstance: true,
  argv: ['demo/profile', '--json'],
  status: 'SUCCEEDED',
  exitCode: 0,
  stdout: '{"answer":42,"items":["one"]}',
  stdoutTruncated: false,
  stderr: null,
  stderrTruncated: false,
  errorMessage: null,
  timeoutMillis: 30000,
  queuedMillis: 18,
  durationMillis: 95,
  resources: [{
    date: '2026-07-13',
    group: resourceGroup,
    relativePath: 'reports/result file.json',
    resourcePath: `2026-07-13/${resourceGroup}/reports/result file.json`,
    fileName: 'result file.json',
    source: 'EXECUTION',
    mimeType: 'application/json',
    size: 42,
    modifiedAt: '2026-07-13T10:00:02',
    contentUrl: '/untrusted-content-url',
    downloadUrl: '/untrusted-download-url',
  }],
  queuedAt: '2026-07-13T10:00:00',
  startedAt: '2026-07-13T10:00:01',
  finishedAt: '2026-07-13T10:00:02',
}

function renderDetail(path = `/executions/${executionId}`) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/executions/:id" element={<ExecutionDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function outputBlocks(): HTMLElement[] {
  return screen.getAllByText((_content, element) => element?.tagName === 'PRE')
}

afterEach(() => vi.clearAllMocks())

describe('ExecutionDetailPage', () => {
  it('pretty-prints JSON stdout, renders persistent affinity guidance, and builds controlled resource links', async () => {
    // The detail view must preserve structured output readability and derive both resource URLs from the safe path helper, not DTO URLs.
    vi.mocked(apiClient.get).mockResolvedValue(execution)

    renderDetail()

    expect(await screen.findByRole('heading', { name: `执行记录 #${executionId}` })).toBeInTheDocument()
    expect(apiClient.get).toHaveBeenCalledWith(`/executions/${executionId}`)
    expect(outputBlocks().map((block) => block.textContent)).toContain('{\n  "answer": 42,\n  "items": [\n    "one"\n  ]\n}')
    expect(screen.getByRole('note')).toHaveTextContent('复用实例（persistent affinity）')
    expect(screen.getByRole('link', { name: '预览 result file.json' })).toHaveAttribute(
      'href',
      `/api/resources/2026-07-13/${resourceGroup}/reports/result%20file.json?inline=true`,
    )
    expect(screen.getByRole('link', { name: '下载 result file.json' })).toHaveAttribute(
      'href',
      `/api/resources/2026-07-13/${resourceGroup}/reports/result%20file.json`,
    )
  })

  it('keeps plain stdout unchanged and exposes stderr plus the execution error message', async () => {
    // Non-JSON process output must not be discarded while terminal failures retain both diagnostic channels.
    vi.mocked(apiClient.get).mockResolvedValue({
      ...execution,
      status: 'FAILED',
      reuseInstance: false,
      stdout: 'not json\nstill useful',
      stderr: 'permission denied',
      stderrTruncated: true,
      errorMessage: 'OpenCLI exited unexpectedly',
      resources: [],
    })

    renderDetail()

    await screen.findByRole('heading', { name: `执行记录 #${executionId}` })
    expect(outputBlocks().map((block) => block.textContent)).toEqual(expect.arrayContaining([
      'not json\nstill useful',
      'permission denied',
      'OpenCLI exited unexpectedly',
    ]))
    expect(screen.getByRole('heading', { name: '错误输出' })).toBeInTheDocument()
    expect(screen.getByText('标准错误已截断')).toBeInTheDocument()
  })

  it('renders convention4j stringified durations when lifecycle timestamps are omitted', async () => {
    // Queued executions omit null lifecycle timestamps under convention4j NON_NULL serialization.
    const pendingExecution: HubExecution = {
      ...execution,
      status: 'PENDING',
      timeoutMillis: '30000',
      queuedMillis: '0',
      durationMillis: '0',
      queuedAt: [2026, 7, 13, 10, 0, 0],
    }
    delete pendingExecution.startedAt
    delete pendingExecution.finishedAt
    vi.mocked(apiClient.get).mockResolvedValue(pendingExecution)

    renderDetail()

    await screen.findByRole('heading', { name: `执行记录 #${executionId}` })
    expect(screen.getByText('超时').parentElement).toHaveTextContent('30000 ms')
    expect(screen.getByText('入队时间').parentElement).toHaveTextContent('2026-07-13 10:00:00')
    expect(screen.getByText('开始时间').parentElement).toHaveTextContent('—')
    expect(screen.getByText('结束时间').parentElement).toHaveTextContent('—')
  })
})
