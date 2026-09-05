import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { listCommands } from '@/features/commands/commands-api'
import { bindInstanceActiveTab, clearInstanceQueue, deleteInstance, getInstance, getInstanceVncStatus, runInstanceLifecycleAction, updateInstance } from '@/features/instances/instances-api'
import { InstanceForm } from '@/features/instances/InstanceForm'
import { InstanceLifecycleActions } from '@/features/instances/InstanceLifecycleActions'
import type { InstanceEditableProperties } from '@/features/instances/types'
import { VncViewer } from '@/features/instances/VncViewer'
import { ConfirmDialog, ErrorState, Loading, StatusBadge } from '@/shared/components'
import { parseBackendId } from '@/shared/api/backend-id'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function proxySummary(proxyMode: 'INHERIT' | 'DIRECT' | 'CUSTOM', proxyServer: string | null): string {
  if (proxyMode === 'INHERIT') return '继承全局'
  if (proxyMode === 'DIRECT') return '直连'
  return proxyServer ? `自定义 · ${proxyServer}` : '自定义'
}

export function InstanceDetailPage() {
  const { id } = useParams<{ id: string }>()
  const instanceId = parseBackendId(id ?? '')
  const validId = instanceId !== undefined
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionSuccess, setActionSuccess] = useState<string | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [confirmBindSite, setConfirmBindSite] = useState<string | null>(null)
  const [selectedSiteState, setSelectedSiteState] = useState<string>('')
  const instanceQuery = useQuery({
    queryKey: ['instance', instanceId],
    queryFn: () => getInstance(instanceId!),
    enabled: validId,
  })
  const vncStatusQuery = useQuery({
    queryKey: ['instance', instanceId, 'vnc-status'],
    queryFn: () => getInstanceVncStatus(instanceId!),
    enabled: validId,
  })
  const commandsQuery = useQuery({
    queryKey: ['commands'],
    queryFn: () => listCommands(),
    enabled: validId,
  })

  const bindableSites = useMemo(() => {
    const enabledWebsites = new Set(instanceQuery.data?.websites ?? [])
    const sites = new Set<string>()
    for (const cmd of commandsQuery.data ?? []) {
      if (cmd.browser === true && cmd.siteSession === 'PERSISTENT' && enabledWebsites.has(cmd.site)) {
        sites.add(cmd.site)
      }
    }
    return Array.from(sites).sort()
  }, [commandsQuery.data, instanceQuery.data?.websites])

  const selectedSite = bindableSites.includes(selectedSiteState)
    ? selectedSiteState
    : (bindableSites[0] ?? '')

  if (!validId) {
    return <ErrorState title="无效的实例 ID" description="实例 ID 不能为空。" />
  }
  const resolvedInstanceId = instanceId

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['instances'] }),
      queryClient.invalidateQueries({ queryKey: ['instance', resolvedInstanceId] }),
      queryClient.invalidateQueries({ queryKey: ['instance', resolvedInstanceId, 'vnc-status'] }),
    ])
  }

  async function runAction(action: 'start' | 'stop' | 'restart') {
    setActionError(null)
    setActionSuccess(null)
    setActionPending(true)
    try {
      await runInstanceLifecycleAction(resolvedInstanceId, action)
      await refresh()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function clearQueue() {
    setActionError(null)
    setActionSuccess(null)
    setActionPending(true)
    try {
      await clearInstanceQueue(resolvedInstanceId)
      await refresh()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function bindActiveTab(site: string) {
    setActionError(null)
    setActionSuccess(null)
    setActionPending(true)
    try {
      await bindInstanceActiveTab(resolvedInstanceId, site)
      setConfirmBindSite(null)
      setActionSuccess(`已将当前 VNC 标签页绑定至 ${site}。`)
      await refresh()
    } catch (error) {
      setConfirmBindSite(null)
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function save(properties: InstanceEditableProperties) {
    setActionError(null)
    setActionSuccess(null)
    setActionPending(true)
    try {
      await updateInstance(resolvedInstanceId, properties)
      setEditing(false)
      await refresh()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function remove() {
    setActionError(null)
    setActionSuccess(null)
    setActionPending(true)
    try {
      await deleteInstance(resolvedInstanceId)
      await queryClient.invalidateQueries({ queryKey: ['instances'] })
      navigate('/instances')
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  if (instanceQuery.isPending) {
    return <Loading label="正在加载实例…" />
  }
  if (instanceQuery.isError) {
    return <ErrorState title="无法加载实例" description={errorMessage(instanceQuery.error)} onRetry={() => void instanceQuery.refetch()} />
  }

  const instance = instanceQuery.data
  const runtime = instance.runtime
  const queueBusy = (runtime?.activeCount ?? 0) + (runtime?.pendingCount ?? 0) > 0
  const canBindActiveTab =
    instance.state === 'RUNNING' &&
    runtime?.registered === true &&
    !queueBusy &&
    !commandsQuery.isPending &&
    !commandsQuery.isError &&
    bindableSites.length > 0 &&
    Boolean(selectedSite)
  const canDelete = !queueBusy && instance.state !== 'STARTING' && instance.state !== 'STOPPING'
  const initialValues: InstanceEditableProperties = {
    code: instance.code,
    displayName: instance.displayName,
    websites: instance.websites ?? [],
    maxConcurrency: instance.maxConcurrency,
    maxPending: instance.maxPending,
    priority: instance.priority ?? 0,
    proxyMode: instance.proxyMode,
    proxyServer: instance.proxyServer,
  }

  return (
    <div className="page instance-detail-page">
      <header className="page-header instance-detail-header detail-hero">
        <div>
          <p className="eyebrow"><Link to="/instances">实例管理</Link> <span aria-hidden="true">/</span> INSTANCE · {instance.code}</p>
          <h1 className="page-title">{instance.displayName}</h1>
          <p className="page-subtitle">浏览器运行状态、认证上下文与远程控制台。</p>
        </div>
        <StatusBadge status={instance.state} />
      </header>
      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}
      {actionSuccess ? <p className="page-success" role="status">{actionSuccess}</p> : null}

      <div className="instance-detail-layout">
        <div className="instance-detail-main">
          <section className="instance-section instance-console-section" aria-labelledby="vnc-status-title">
            <div className="section-heading-row">
              <div>
                <p className="eyebrow">REMOTE DESKTOP</p>
                <h2 id="vnc-status-title">浏览器控制台</h2>
              </div>
              <button type="button" className="btn" disabled={vncStatusQuery.isFetching} onClick={() => void vncStatusQuery.refetch()}>刷新 VNC 状态</button>
            </div>
            {vncStatusQuery.isPending ? <p className="muted" role="status">正在加载 VNC 状态…</p> : null}
            {vncStatusQuery.isError ? <p className="inline-error" role="alert">{errorMessage(vncStatusQuery.error)}</p> : null}
            {vncStatusQuery.isSuccess ? (
              <dl className="metadata-grid vnc-health-grid">
                <div><dt>实例存在</dt><dd>{vncStatusQuery.data.instanceAvailable ? '是' : '否'}</dd></div>
                <div><dt>正在运行</dt><dd>{vncStatusQuery.data.running ? '是' : '否'}</dd></div>
                <div><dt>运行时可用</dt><dd>{vncStatusQuery.data.runtimeAvailable ? '是' : '否'}</dd></div>
                <div><dt>VNC 可用</dt><dd>{vncStatusQuery.data.vncAvailable ? '是' : '否'}</dd></div>
              </dl>
            ) : null}
            <VncViewer key={resolvedInstanceId} instanceId={resolvedInstanceId} available={vncStatusQuery.data?.vncAvailable === true} />
          </section>
        </div>

        <aside className="instance-detail-sidebar">
          <section className="instance-section" aria-labelledby="instance-summary-title">
            <div className="section-heading-row">
              <div>
                <p className="eyebrow">RUNTIME</p>
                <h2 id="instance-summary-title">实例状态</h2>
              </div>
              <button type="button" className="btn" disabled={actionPending} onClick={() => setEditing((value) => !value)}>{editing ? '取消编辑' : '编辑'}</button>
            </div>
            <dl className="metadata-grid instance-summary-grid">
              <div><dt>实例代码</dt><dd>{instance.code}</dd></div>
              <div><dt>网站</dt><dd>{instance.websites?.join(', ') || '未配置'}</dd></div>
              <div><dt>Context ID</dt><dd className="mono-value" title={instance.contextId ?? undefined}>{instance.contextId || '未分配'}</dd></div>
              <div><dt>显示器</dt><dd>{runtime?.registered ? `:${runtime.displayNumber ?? '—'}` : '运行时未注册'}</dd></div>
              <div><dt>执行队列</dt><dd>活跃 {runtime?.activeCount ?? 0}/{instance.maxConcurrency} · 待处理 {runtime?.pendingCount ?? 0}/{instance.maxPending}</dd></div>
              <div><dt>优先级</dt><dd>{instance.priority ?? 0}</dd></div>
              <div><dt>代理</dt><dd title={instance.proxyServer ?? undefined}>{proxySummary(instance.proxyMode, instance.proxyServer)}</dd></div>
            </dl>
            {instance.lastErrorMessage ? <p className="inline-error instance-error" role="alert">最近错误：{instance.lastErrorMessage}</p> : null}
            <div className="instance-sidebar-actions">
              <InstanceLifecycleActions instance={instance} busy={actionPending} onAction={(action) => void runAction(action)} />
              <select
                aria-label="绑定目标网站"
                value={selectedSite}
                disabled={actionPending || !canBindActiveTab}
                onChange={(event) => setSelectedSiteState(event.target.value)}
              >
                {commandsQuery.isPending ? (
                  <option value="">正在加载网站…</option>
                ) : commandsQuery.isError ? (
                  <option value="">网站加载失败</option>
                ) : bindableSites.length === 0 ? (
                  <option value="">无可绑定网站</option>
                ) : (
                  bindableSites.map((site) => (
                    <option key={site} value={site}>
                      {site}
                    </option>
                  ))
                )}
              </select>
              <button
                type="button"
                className="btn"
                disabled={actionPending || !canBindActiveTab}
                title={selectedSite ? `先在 VNC 中选中目标 ${selectedSite} 标签页；运行中的任务不能绑定` : '无可绑定网站'}
                onClick={() => setConfirmBindSite(selectedSite)}
              >
                绑定当前 VNC 标签页
              </button>
              <button
                type="button"
                className="btn"
                disabled={actionPending || (runtime?.pendingCount ?? 0) === 0}
                title="拒绝所有排队中的执行；不影响当前正在执行的任务"
                onClick={() => void clearQueue()}
              >
                清空排队{(runtime?.pendingCount ?? 0) > 0 ? ` (${runtime?.pendingCount})` : ''}
              </button>
              <div className="instance-sidebar-secondary">
                <Link className="btn" to={`/logs?instanceId=${encodeURIComponent(instance.id)}`}>查看日志</Link>
                <button
                  type="button"
                  className="btn btn-quiet-danger"
                  disabled={actionPending || !canDelete}
                  title="删除实例"
                  onClick={() => setConfirmDelete(true)}
                >
                  删除
                </button>
              </div>
            </div>
          </section>

          {editing ? (
            <section className="instance-section" aria-labelledby="edit-instance-title">
              <h2 id="edit-instance-title">编辑实例</h2>
              <InstanceForm initialValues={initialValues} submitLabel="保存更改" busy={actionPending} onSubmit={save} onCancel={() => setEditing(false)} />
            </section>
          ) : null}
        </aside>
      </div>

      <ConfirmDialog
        open={Boolean(confirmBindSite)}
        title={`绑定 ${confirmBindSite ?? ''} 标签页？`}
        description={
          confirmBindSite ? (
            <>
              请先在 VNC 中选中目标 <strong>{confirmBindSite}</strong> 标签页。此操作会将当前活动 VNC 标签页绑定为{' '}
              <strong>{confirmBindSite}</strong> 的持久受管标签页，不会关闭目标用户标签页；运行中的任务不能绑定。
            </>
          ) : undefined
        }
        confirmLabel={confirmBindSite ? `确认绑定 ${confirmBindSite}` : '确认绑定'}
        busy={actionPending}
        onConfirm={() => {
          if (confirmBindSite) void bindActiveTab(confirmBindSite)
        }}
        onCancel={() => setConfirmBindSite(null)}
      />

      <ConfirmDialog
        open={confirmDelete}
        title="删除实例？"
        description={<>删除 <strong>{instance.displayName}</strong> 会永久移除其 Profile 和浏览器登录状态，且无法恢复。</>}
        confirmLabel="删除实例"
        tone="danger"
        busy={actionPending}
        onConfirm={() => void remove()}
        onCancel={() => setConfirmDelete(false)}
      />
    </div>
  )
}
