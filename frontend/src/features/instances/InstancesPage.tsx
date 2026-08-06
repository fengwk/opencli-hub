import { Activity, CirclePlus, Monitor, Server, SquareTerminal, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { createInstance, deleteInstance, listInstances, runInstanceLifecycleAction } from '@/features/instances/instances-api'
import { InstanceForm } from '@/features/instances/InstanceForm'
import { InstanceLifecycleActions } from '@/features/instances/InstanceLifecycleActions'
import type { HubInstance, InstanceEditableProperties } from '@/features/instances/types'
import { ConfirmDialog, Empty, ErrorState, Loading, StatusBadge } from '@/shared/components'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function isIdle(instance: HubInstance): boolean {
  return (instance.runtime?.activeCount ?? 0) + (instance.runtime?.pendingCount ?? 0) === 0
}

function addPendingInstance(set: Dispatch<SetStateAction<Set<HubInstance['id']>>>, instanceId: HubInstance['id']) {
  set((pending) => {
    const next = new Set(pending)
    next.add(instanceId)
    return next
  })
}

function removePendingInstance(set: Dispatch<SetStateAction<Set<HubInstance['id']>>>, instanceId: HubInstance['id']) {
  set((pending) => {
    const next = new Set(pending)
    next.delete(instanceId)
    return next
  })
}

const instanceStateLabels: Record<HubInstance['state'], string> = {
  STARTING: '启动中',
  RUNNING: '运行中',
  STOPPING: '停止中',
  STOPPED: '已停止',
  ERROR: '异常',
}

function InstanceCard({
  instance,
  busy,
  onLifecycle,
  onDelete,
}: {
  instance: HubInstance
  busy: boolean
  onLifecycle: (instance: HubInstance, action: 'start' | 'stop' | 'restart') => void
  onDelete: (instance: HubInstance) => void
}) {
  const runtime = instance.runtime
  const vncAvailable = instance.state === 'RUNNING' && runtime?.registered === true && (runtime.vncPort ?? 0) > 0
  const canDelete = isIdle(instance) && instance.state !== 'STARTING' && instance.state !== 'STOPPING'

  const previewSites = instance.websites?.slice(0, 3) ?? []
  const stateLabel = instanceStateLabels[instance.state]

  return (
    <article className="instance-card">
      <div className={`instance-card-preview state-${instance.state.toLowerCase()}`} aria-hidden="true">
        <div className="instance-preview-meta">
          <span className="instance-preview-state"><i />{stateLabel}</span>
          <span>{runtime?.registered ? `DISPLAY :${runtime.displayNumber ?? '—'}` : 'RUNTIME OFFLINE'}</span>
        </div>
        <div className="browser-preview-window">
          <div className="browser-preview-bar">
            <span className="browser-preview-dots"><i /><i /><i /></span>
            <b>{instance.contextId ? 'bridge connected' : 'waiting for context'}</b>
          </div>
          <div className="browser-preview-body">
            <div className="browser-preview-sites">
              {previewSites.length ? previewSites.map((site) => <span key={site}>{site}</span>) : <span>no sites configured</span>}
            </div>
            <div className="browser-preview-signal"><i /><i /><i /></div>
          </div>
        </div>
      </div>
      <header className="instance-card-header">
        <div>
          <p className="eyebrow">实例 #{instance.id} · {instance.code}</p>
          <h2 className="card-title"><Link to={`/instances/${encodeURIComponent(instance.id)}`}>{instance.displayName}</Link></h2>
        </div>
        <StatusBadge status={instance.state} label={stateLabel} />
      </header>
      <div className="site-chip-row" aria-label="支持的网站">
        {instance.websites?.length ? instance.websites.map((site) => <span className="site-chip" key={site}>{site}</span>) : <span className="muted">未配置网站</span>}
      </div>
      <dl className="metadata-grid instance-metadata">
        <div><dt>运行时</dt><dd>{runtime?.registered ? `已注册 · 显示器 :${runtime.displayNumber ?? '—'}` : '等待注册'}</dd></div>
        <div><dt>远程控制</dt><dd>{vncAvailable ? 'VNC 可连接' : 'VNC 不可用'}</dd></div>
        <div><dt>执行队列</dt><dd>活跃 {runtime?.activeCount ?? 0} · 待处理 {runtime?.pendingCount ?? 0}/{instance.maxPending}</dd></div>
        <div><dt>优先级</dt><dd>{instance.priority ?? 0}</dd></div>
        <div><dt>会话上下文</dt><dd className="mono-value" title={instance.contextId ?? undefined}>{instance.contextId || '尚未分配'}</dd></div>
      </dl>
      {instance.lastErrorMessage ? <p className="inline-error instance-error" role="alert">最近错误：{instance.lastErrorMessage}</p> : null}
      <footer className="instance-card-footer">
        <Link className="btn btn-primary" to={`/instances/${encodeURIComponent(instance.id)}`}><SquareTerminal aria-hidden="true" />详情与控制台</Link>
        <InstanceLifecycleActions compact instance={instance} busy={busy} onAction={(action) => onLifecycle(instance, action)} />
        <button type="button" className="btn btn-danger btn-quiet-danger" disabled={busy || !canDelete} onClick={() => onDelete(instance)}>删除</button>
      </footer>
    </article>
  )
}

function InstanceMetrics({ instances }: { instances: HubInstance[] }) {
  const running = instances.filter((instance) => instance.state === 'RUNNING').length
  const waiting = instances.reduce((count, instance) => count + (instance.runtime?.pendingCount ?? 0), 0)
  const vncReady = instances.filter((instance) => instance.state === 'RUNNING' && instance.runtime?.registered && (instance.runtime.vncPort ?? 0) > 0).length
  const errors = instances.filter((instance) => instance.state === 'ERROR' || instance.lastErrorMessage).length
  const metrics = [
    { label: '全部实例', value: instances.length, helper: errors ? `${errors} 个需关注` : '已纳入管理', icon: Server },
    { label: '正在运行', value: running, helper: '处于 RUNNING 状态', icon: Activity },
    { label: '等待任务', value: waiting, helper: '队列中的执行', icon: SquareTerminal },
    { label: 'VNC 就绪', value: vncReady, helper: running ? `运行实例共 ${running} 个` : '暂无运行实例', icon: Monitor },
  ]

  return (
    <section className="metric-grid" aria-label="实例概览">
      {metrics.map(({ label, value, helper, icon: Icon }) => (
        <article className="metric-card" key={label}>
          <Icon aria-hidden="true" />
          <div><span>{label}</span><strong>{value}</strong><small>{helper}</small></div>
        </article>
      ))}
    </section>
  )
}

function CreateInstancePanel({ busy, error, onClose, onSubmit }: {
  busy: boolean
  error: string | null
  onClose: () => void
  onSubmit: (properties: InstanceEditableProperties) => Promise<void>
}) {
  const panelRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        onClose()
        return
      }
      if (event.key !== 'Tab') return

      const panel = panelRef.current
      const focusable = panel
        ? Array.from(panel.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ))
        : []
      if (!panel) return
      if (focusable.length === 0) {
        event.preventDefault()
        panel.focus()
        return
      }

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (!panel.contains(document.activeElement)) {
        event.preventDefault()
        const target = event.shiftKey ? last : first
        target.focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [busy, onClose])

  return (
    <div className="dialog-backdrop create-panel-backdrop" onClick={busy ? undefined : onClose}>
      <section
        ref={panelRef}
        className="dialog create-instance-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-instance-title"
        aria-describedby="create-instance-description"
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="section-heading-row">
          <div>
            <p className="eyebrow">NEW INSTANCE</p>
            <h2 id="create-instance-title" className="dialog-title">创建浏览器实例</h2>
            <p id="create-instance-description" className="dialog-body">配置可认证的网站与队列上限。提交后 Hub 会立即启动浏览器运行时并等待扩展绑定 contextId。</p>
          </div>
          <button type="button" className="icon-button" aria-label="关闭创建实例面板" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </div>
        {error ? <p className="page-error" role="alert">{error}</p> : null}
        <InstanceForm autoFocus submitLabel="创建实例" busy={busy} onSubmit={onSubmit} onCancel={onClose} />
      </section>
    </div>
  )
}

export function InstancesPage() {
  const queryClient = useQueryClient()
  const [actionError, setActionError] = useState<string | null>(null)
  const [pendingInstanceIds, setPendingInstanceIds] = useState<Set<HubInstance['id']>>(() => new Set())
  const [createPending, setCreatePending] = useState(false)
  const [deletePendingId, setDeletePendingId] = useState<HubInstance['id'] | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<HubInstance | null>(null)
  const createTriggerRef = useRef<HTMLButtonElement | null>(null)
  const instancesQuery = useQuery({ queryKey: ['instances'], queryFn: listInstances })
  const actionPending = createPending || pendingInstanceIds.size > 0 || deletePendingId !== null

  useEffect(() => {
    if (!createOpen) createTriggerRef.current?.focus()
  }, [createOpen])

  async function refreshInstances() {
    await queryClient.invalidateQueries({ queryKey: ['instances'] })
  }

  function openCreatePanel(trigger: HTMLButtonElement) {
    createTriggerRef.current = trigger
    setActionError(null)
    setCreateOpen(true)
  }

  function closeCreatePanel() {
    setActionError(null)
    setCreateOpen(false)
  }

  async function handleCreate(properties: InstanceEditableProperties) {
    setActionError(null)
    setCreatePending(true)
    try {
      await createInstance(properties)
      closeCreatePanel()
      await refreshInstances()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setCreatePending(false)
    }
  }

  async function handleLifecycle(instance: HubInstance, action: 'start' | 'stop' | 'restart') {
    setActionError(null)
    addPendingInstance(setPendingInstanceIds, instance.id)
    try {
      const updatedInstance = await runInstanceLifecycleAction(instance.id, action)
      queryClient.setQueryData<HubInstance[]>(['instances'], (instances) => (
        instances?.map((current) => current.id === updatedInstance.id ? updatedInstance : current)
      ))
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      removePendingInstance(setPendingInstanceIds, instance.id)
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return
    const target = deleteTarget
    setActionError(null)
    setDeletePendingId(target.id)
    try {
      await deleteInstance(target.id)
      setDeleteTarget(null)
      await refreshInstances()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setDeletePendingId(null)
    }
  }

  return (
    <div className="page">
      <header className="page-header page-header-actions fleet-hero">
        <div>
          <p className="eyebrow"><span className="status-dot" />BROWSER FLEET</p>
          <h1 className="page-title">实例管理</h1>
          <p className="page-subtitle">统一管理承载登录状态的 OpenCLI 浏览器、执行队列与远程控制台。</p>
        </div>
        <button type="button" className="btn btn-primary" disabled={actionPending} onClick={(event) => openCreatePanel(event.currentTarget)}><CirclePlus aria-hidden="true" />创建实例</button>
      </header>

      {actionError && !createOpen ? <p className="page-error" role="alert">{actionError}</p> : null}
      {instancesQuery.isPending ? <Loading label="正在加载实例…" /> : null}
      {instancesQuery.isError ? <ErrorState title="无法加载实例" description={errorMessage(instancesQuery.error)} onRetry={() => void instancesQuery.refetch()} /> : null}
      {instancesQuery.isSuccess ? <InstanceMetrics instances={instancesQuery.data} /> : null}
      {instancesQuery.isSuccess && instancesQuery.data.length === 0 ? <Empty title="暂无实例" description="创建实例后，可在这里管理其生命周期和浏览器状态。" action={<button type="button" className="btn btn-primary" disabled={actionPending} onClick={(event) => openCreatePanel(event.currentTarget)}>创建首个实例</button>} /> : null}
      {instancesQuery.isSuccess && instancesQuery.data.length > 0 ? (
        <section className="instance-list" aria-label="实例列表">
          {instancesQuery.data.map((instance) => (
            <InstanceCard
              key={instance.id}
              instance={instance}
              busy={pendingInstanceIds.has(instance.id) || deletePendingId === instance.id}
              onLifecycle={handleLifecycle}
              onDelete={setDeleteTarget}
            />
          ))}
        </section>
      ) : null}

      {createOpen ? <CreateInstancePanel busy={createPending} error={actionError} onClose={closeCreatePanel} onSubmit={handleCreate} /> : null}
      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除实例？"
        description={<>删除 <strong>{deleteTarget?.displayName}</strong> 会永久移除其 Profile 和浏览器登录状态，且无法恢复。</>}
        confirmLabel="删除实例"
        tone="danger"
        busy={deletePendingId !== null}
        onConfirm={() => void handleDelete()}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
