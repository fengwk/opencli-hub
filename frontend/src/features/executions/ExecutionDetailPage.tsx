import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getExecution } from '@/features/executions/executions-api'
import { formatDateTime, formatMillis, formatStdout } from '@/features/executions/execution-format'
import { parseBackendId } from '@/shared/api/backend-id'
import { buildResourceUrl } from '@/shared/api/resource-url'
import { Empty, ErrorState, Loading, StatusBadge } from '@/shared/components'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function Metadata({ label, value }: { label: string, value: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

export function ExecutionDetailPage() {
  const { id } = useParams<{ id: string }>()
  const executionId = parseBackendId(id ?? '')
  const validId = executionId !== undefined
  const executionQuery = useQuery({
    queryKey: ['execution', executionId],
    queryFn: () => getExecution(executionId!),
    enabled: validId,
  })

  if (!validId) {
    return (
      <div className="page">
        <ErrorState title="无效的 Execution ID" description="Execution ID 不能为空。" />
      </div>
    )
  }

  if (executionQuery.isPending) {
    return <div className="page"><Loading label="正在加载执行详情…" /></div>
  }

  if (executionQuery.isError) {
    return (
      <div className="page">
        <ErrorState title="无法加载执行详情" description={errorMessage(executionQuery.error)} onRetry={() => void executionQuery.refetch()} />
      </div>
    )
  }

  const execution = executionQuery.data
  if (!execution) {
    return <div className="page"><Empty title="未找到执行记录" /></div>
  }

  const stdout = formatStdout(execution.stdout)

  return (
    <div className="page">
      <header className="page-header execution-detail-header">
        <div>
          <p className="eyebrow">EXECUTION DETAIL</p>
          <h1 className="page-title">执行记录 #{execution.id}</h1>
          <p className="page-subtitle">{execution.commandKey ?? '未记录命令'}</p>
        </div>
        <StatusBadge status={execution.status} />
      </header>

      <Link className="btn compact-button" to="/executions">返回执行记录</Link>

      {execution.reuseInstance ? (
        <aside className="execution-guidance" role="note">
          <strong>后续复用实例（persistent affinity）</strong>
          <span>该站点会话需要保持粘性；后续请求请显式携带本次返回的 Instance ID，以继续使用同一登录状态。</span>
        </aside>
      ) : null}

      <dl className="metadata-grid">
        <Metadata label="状态" value={<StatusBadge status={execution.status} />} />
        <Metadata label="Instance" value={execution.instanceCode ?? '未分配'} />
        <Metadata label="Instance ID" value={execution.instanceId ?? '—'} />
        <Metadata label="命令" value={execution.commandKey ?? '—'} />
        <Metadata label="站点" value={execution.site ?? '—'} />
        <Metadata label="Session" value={execution.siteSession ?? '—'} />
        <Metadata label="复用 Instance" value={execution.reuseInstance ? '是' : '否'} />
        <Metadata label="退出码" value={execution.exitCode ?? '—'} />
        <Metadata label="超时" value={formatMillis(execution.timeoutMillis)} />
        <Metadata label="排队耗时" value={formatMillis(execution.queuedMillis)} />
        <Metadata label="执行耗时" value={formatMillis(execution.durationMillis)} />
        <Metadata label="入队时间" value={formatDateTime(execution.queuedAt)} />
        <Metadata label="开始时间" value={formatDateTime(execution.startedAt)} />
        <Metadata label="结束时间" value={formatDateTime(execution.finishedAt)} />
      </dl>

      <section className="execution-section">
        <h2>参数</h2>
        {execution.argv?.length ? <ol className="argument-list">{execution.argv.map((argument, index) => <li key={`${index}-${argument}`}><code>{argument}</code></li>)}</ol> : <p className="muted">未记录命令参数。</p>}
      </section>

      <section className="execution-section">
        <div className="section-heading-row">
          <h2>标准输出</h2>
          {execution.stdoutTruncated ? <span className="muted">输出已截断</span> : null}
        </div>
        {stdout ? <pre className="execution-output">{stdout}</pre> : <p className="muted">没有标准输出。</p>}
      </section>

      {execution.stderr || execution.errorMessage ? (
        <section className="execution-section execution-error-output">
          <h2>错误输出</h2>
          {execution.stderr ? <><h3>标准错误</h3><pre className="execution-output">{execution.stderr}</pre></> : null}
          {execution.stderrTruncated ? <p className="muted">标准错误已截断</p> : null}
          {execution.errorMessage ? <><h3>错误信息</h3><pre className="execution-output">{execution.errorMessage}</pre></> : null}
        </section>
      ) : null}

      <section className="execution-section">
        <h2>资源</h2>
        {execution.resources?.length ? (
          <ul className="resource-list">
            {execution.resources.map((resource) => {
              const previewUrl = buildResourceUrl(resource, { inline: true })
              const downloadUrl = buildResourceUrl(resource)
              return (
                <li className="resource-item" key={resource.resourcePath}>
                  <div className="resource-item-details">
                    <strong>{resource.relativePath || resource.fileName}</strong>
                    <span>{resource.mimeType} · {resource.size} B · {formatDateTime(resource.modifiedAt)}</span>
                  </div>
                  <div className="resource-actions">
                    <a className="btn" href={previewUrl} target="_blank" rel="noreferrer">预览 {resource.fileName}</a>
                    <a className="btn" href={downloadUrl}>下载 {resource.fileName}</a>
                  </div>
                </li>
              )
            })}
          </ul>
        ) : <p className="muted">该执行没有产生资源。</p>}
      </section>
    </div>
  )
}
