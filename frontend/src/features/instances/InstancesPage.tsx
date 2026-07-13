import { useState } from 'react'
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

  return (
    <article className="instance-card">
      <header className="instance-card-header">
        <div>
          <h2 className="card-title"><Link to={`/instances/${instance.id}`}>{instance.displayName}</Link></h2>
          <p className="card-description">{instance.code}</p>
        </div>
        <StatusBadge status={instance.state} />
      </header>
      <dl className="metadata-grid">
        <div><dt>网站</dt><dd>{instance.websites?.join(', ') || '未配置'}</dd></div>
        <div><dt>Context ID</dt><dd>{instance.contextId || '未分配'}</dd></div>
        <div><dt>运行时</dt><dd>{runtime?.registered ? `已注册（显示器 :${runtime.displayNumber ?? '—'}）` : '未注册'}</dd></div>
        <div><dt>VNC</dt><dd>{vncAvailable ? '可用' : '不可用'}</dd></div>
        <div><dt>执行队列</dt><dd>活跃 {runtime?.activeCount ?? 0} / 待处理 {runtime?.pendingCount ?? 0}（上限 {instance.maxPending}）</dd></div>
      </dl>
      {instance.lastErrorMessage ? <p className="inline-error" role="alert">最近错误：{instance.lastErrorMessage}</p> : null}
      <div className="instance-card-footer">
        <Link className="btn" to={`/instances/${instance.id}`}>查看详情</Link>
        <InstanceLifecycleActions instance={instance} busy={busy} onAction={(action) => onLifecycle(instance, action)} />
        <button type="button" className="btn btn-danger" disabled={busy || !canDelete} onClick={() => onDelete(instance)}>删除</button>
      </div>
    </article>
  )
}

export function InstancesPage() {
  const queryClient = useQueryClient()
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<HubInstance | null>(null)
  const instancesQuery = useQuery({ queryKey: ['instances'], queryFn: listInstances })

  async function refreshInstances() {
    await queryClient.invalidateQueries({ queryKey: ['instances'] })
  }

  async function handleCreate(properties: InstanceEditableProperties) {
    setActionError(null)
    setActionPending(true)
    try {
      await createInstance(properties)
      await refreshInstances()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function handleLifecycle(instance: HubInstance, action: 'start' | 'stop' | 'restart') {
    setActionError(null)
    setActionPending(true)
    try {
      await runInstanceLifecycleAction(instance.id, action)
      await refreshInstances()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function handleDelete() {
    if (!deleteTarget) {
      return
    }
    setActionError(null)
    setActionPending(true)
    try {
      await deleteInstance(deleteTarget.id)
      setDeleteTarget(null)
      await refreshInstances()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1 className="page-title">Instances</h1>
        <p className="page-subtitle">创建并管理携带浏览器登录状态的 OpenCLI 实例。</p>
      </header>

      <section className="instance-section" aria-labelledby="create-instance-title">
        <h2 id="create-instance-title">创建实例</h2>
        <InstanceForm submitLabel="创建实例" busy={actionPending} onSubmit={handleCreate} />
      </section>

      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}
      {instancesQuery.isPending ? <Loading label="正在加载实例…" /> : null}
      {instancesQuery.isError ? <ErrorState title="无法加载实例" description={errorMessage(instancesQuery.error)} onRetry={() => void instancesQuery.refetch()} /> : null}
      {instancesQuery.isSuccess && instancesQuery.data.length === 0 ? <Empty title="暂无实例" description="创建实例后，可在这里管理其生命周期和浏览器状态。" /> : null}
      {instancesQuery.isSuccess && instancesQuery.data.length > 0 ? (
        <section className="instance-list" aria-label="实例列表">
          {instancesQuery.data.map((instance) => (
            <InstanceCard
              key={instance.id}
              instance={instance}
              busy={actionPending}
              onLifecycle={handleLifecycle}
              onDelete={setDeleteTarget}
            />
          ))}
        </section>
      ) : null}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除实例？"
        description={<>删除 <strong>{deleteTarget?.displayName}</strong> 会永久移除其 Profile 和浏览器登录状态，且无法恢复。</>}
        confirmLabel="删除实例"
        tone="danger"
        busy={actionPending}
        onConfirm={() => void handleDelete()}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
