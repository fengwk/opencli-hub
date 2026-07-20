import { useState } from 'react'
import type { FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listExecutions } from '@/features/executions/executions-api'
import { formatDateTime, formatMillis } from '@/features/executions/execution-format'
import { Empty, ErrorState, Loading, StatusBadge } from '@/shared/components'
import { parseBackendId } from '@/shared/api/backend-id'
import type { BackendId } from '@/shared/api/contracts'

const defaultPageSize = 20

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function parseInstanceId(value: string): BackendId | undefined {
  return parseBackendId(value)
}

export function ExecutionsPage() {
  const [pageNumber, setPageNumber] = useState(1)
  const [pageSize, setPageSize] = useState(defaultPageSize)
  const [instanceInput, setInstanceInput] = useState('')
  const [instanceId, setInstanceId] = useState<BackendId | undefined>()
  const executionsQuery = useQuery({
    queryKey: ['executions', pageNumber, pageSize, instanceId],
    queryFn: () => listExecutions({ pageNumber, pageSize, instanceId }),
  })
  const totalCount = Number(executionsQuery.data?.totalCount ?? 0)
  const hasPreviousPage = pageNumber > 1
  const hasNextPage = pageNumber * pageSize < totalCount

  function submitFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPageNumber(1)
    setInstanceId(parseInstanceId(instanceInput))
  }

  function changePageSize(nextPageSize: number) {
    setPageSize(nextPageSize)
    setPageNumber(1)
  }

  return (
    <div className="page">
      <header className="page-header">
        <p className="eyebrow">EXECUTION HISTORY</p>
        <h1 className="page-title">执行记录</h1>
        <p className="page-subtitle">查看 OpenCLI 命令的执行历史、状态和耗时。</p>
      </header>

      <form className="filter-bar" aria-label="Execution 筛选" onSubmit={submitFilter}>
        <label>
          Instance ID
          <input
            value={instanceInput}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => setInstanceInput(event.target.value)}
          />
        </label>
        <label>
          每页数量
          <select value={pageSize} onChange={(event) => changePageSize(Number(event.target.value))}>
            <option value={20}>20</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
          </select>
        </label>
        <button type="submit" className="btn btn-primary">筛选</button>
      </form>

      {executionsQuery.isPending ? <Loading label="正在加载执行历史…" /> : null}
      {executionsQuery.isError ? <ErrorState title="无法加载执行历史" description={errorMessage(executionsQuery.error)} onRetry={() => void executionsQuery.refetch()} /> : null}
      {executionsQuery.isSuccess && executionsQuery.data.results.length === 0 ? <Empty title="暂无执行记录" description="命令执行完成后会在这里显示。" /> : null}
      {executionsQuery.isSuccess && executionsQuery.data.results.length > 0 ? (
        <>
          <div className="execution-table-wrap">
            <table className="execution-table">
              <thead>
                <tr>
                  <th scope="col">状态</th>
                  <th scope="col">Instance</th>
                  <th scope="col">命令</th>
                  <th scope="col">耗时</th>
                  <th scope="col">时间</th>
                  <th scope="col"><span className="visually-hidden">操作</span></th>
                </tr>
              </thead>
              <tbody>
                {executionsQuery.data.results.map((execution) => (
                  <tr key={execution.id}>
                    <td data-label="状态"><StatusBadge status={execution.status} /></td>
                    <td data-label="Instance">{execution.instanceCode ?? '未分配'}{execution.instanceId !== null ? ` (#${execution.instanceId})` : ''}</td>
                    <td data-label="命令">{execution.commandKey ?? '—'}</td>
                    <td data-label="耗时">{formatMillis(execution.durationMillis)}</td>
                    <td data-label="时间">{formatDateTime(execution.finishedAt ?? execution.startedAt ?? execution.queuedAt)}</td>
                    <td data-label="操作"><Link className="btn compact-button" to={`/executions/${encodeURIComponent(execution.id)}`}>查看详情</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav className="pagination" aria-label="Execution 分页">
            <span>第 {pageNumber} 页，共 {Number.isFinite(totalCount) ? totalCount : 0} 条</span>
            <div className="pagination-actions">
              <button type="button" className="btn" disabled={!hasPreviousPage} onClick={() => setPageNumber((current) => current - 1)}>上一页</button>
              <button type="button" className="btn" disabled={!hasNextPage} onClick={() => setPageNumber((current) => current + 1)}>下一页</button>
            </div>
          </nav>
        </>
      ) : null}
    </div>
  )
}
