import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { deleteInstance, getInstance, getInstanceVncStatus, runInstanceLifecycleAction, updateInstance } from '@/features/instances/instances-api'
import { InstanceForm } from '@/features/instances/InstanceForm'
import { InstanceLifecycleActions } from '@/features/instances/InstanceLifecycleActions'
import type { InstanceEditableProperties } from '@/features/instances/types'
import { VncViewer } from '@/features/instances/VncViewer'
import { ConfirmDialog, ErrorState, Loading, StatusBadge } from '@/shared/components'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

export function InstanceDetailPage() {
  const { id } = useParams<{ id: string }>()
  const instanceId = Number(id)
  const validId = Number.isSafeInteger(instanceId) && instanceId > 0
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const instanceQuery = useQuery({
    queryKey: ['instance', instanceId],
    queryFn: () => getInstance(instanceId),
    enabled: validId,
  })
  const vncStatusQuery = useQuery({
    queryKey: ['instance', instanceId, 'vnc-status'],
    queryFn: () => getInstanceVncStatus(instanceId),
    enabled: validId,
  })

  if (!validId) {
    return <ErrorState title="无效的实例 ID" description="实例 ID 必须是正整数。" />
  }

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['instances'] }),
      queryClient.invalidateQueries({ queryKey: ['instance', instanceId] }),
      queryClient.invalidateQueries({ queryKey: ['instance', instanceId, 'vnc-status'] }),
    ])
  }

  async function runAction(action: 'start' | 'stop' | 'restart') {
    setActionError(null)
    setActionPending(true)
    try {
      await runInstanceLifecycleAction(instanceId, action)
      await refresh()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function save(properties: InstanceEditableProperties) {
    setActionError(null)
    setActionPending(true)
    try {
      await updateInstance(instanceId, properties)
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
    setActionPending(true)
    try {
      await deleteInstance(instanceId)
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
  const canDelete = !queueBusy && instance.state !== 'STARTING' && instance.state !== 'STOPPING'
  const initialValues: InstanceEditableProperties = {
    code: instance.code,
    displayName: instance.displayName,
    websites: instance.websites ?? [],
    maxPending: instance.maxPending,
  }

  return (
    <div className="page">
      <header className="page-header instance-detail-header">
        <div>
          <p className="page-subtitle"><Link to="/instances">Instances</Link> / {instance.code}</p>
          <h1 className="page-title">{instance.displayName}</h1>
        </div>
        <StatusBadge status={instance.state} />
      </header>
      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}

      <section className="instance-section" aria-labelledby="instance-summary-title">
        <div className="section-heading-row">
          <h2 id="instance-summary-title">实例状态</h2>
          <div className="instance-actions">
            <InstanceLifecycleActions instance={instance} busy={actionPending} onAction={(action) => void runAction(action)} />
            <button type="button" className="btn" disabled={actionPending} onClick={() => setEditing((value) => !value)}>{editing ? '取消编辑' : '编辑'}</button>
            <button type="button" className="btn btn-danger" disabled={actionPending || !canDelete} onClick={() => setConfirmDelete(true)}>删除</button>
          </div>
        </div>
        <dl className="metadata-grid">
          <div><dt>实例代码</dt><dd>{instance.code}</dd></div>
          <div><dt>网站</dt><dd>{instance.websites?.join(', ') || '未配置'}</dd></div>
          <div><dt>Context ID</dt><dd>{instance.contextId || '未分配'}</dd></div>
          <div><dt>显示器</dt><dd>{runtime?.registered ? `:${runtime.displayNumber ?? '—'}` : '运行时未注册'}</dd></div>
          <div><dt>执行队列</dt><dd>活跃 {runtime?.activeCount ?? 0} / 待处理 {runtime?.pendingCount ?? 0}（上限 {instance.maxPending}）</dd></div>
        </dl>
        {instance.lastErrorMessage ? <p className="inline-error" role="alert">最近错误：{instance.lastErrorMessage}</p> : null}
        <Link className="btn" to={`/logs?instanceId=${encodeURIComponent(String(instance.id))}`}>查看日志</Link>
      </section>

      {editing ? (
        <section className="instance-section" aria-labelledby="edit-instance-title">
          <h2 id="edit-instance-title">编辑实例</h2>
          <InstanceForm initialValues={initialValues} submitLabel="保存更改" busy={actionPending} onSubmit={save} onCancel={() => setEditing(false)} />
        </section>
      ) : null}

      <section className="instance-section" aria-labelledby="vnc-status-title">
        <div className="section-heading-row">
          <h2 id="vnc-status-title">VNC 状态</h2>
          <button type="button" className="btn" disabled={vncStatusQuery.isFetching} onClick={() => void vncStatusQuery.refetch()}>刷新 VNC 状态</button>
        </div>
        {vncStatusQuery.isPending ? <p className="muted" role="status">正在加载 VNC 状态…</p> : null}
        {vncStatusQuery.isError ? <p className="inline-error" role="alert">{errorMessage(vncStatusQuery.error)}</p> : null}
        {vncStatusQuery.isSuccess ? (
          <dl className="metadata-grid">
            <div><dt>实例存在</dt><dd>{vncStatusQuery.data.instanceAvailable ? '是' : '否'}</dd></div>
            <div><dt>正在运行</dt><dd>{vncStatusQuery.data.running ? '是' : '否'}</dd></div>
            <div><dt>运行时可用</dt><dd>{vncStatusQuery.data.runtimeAvailable ? '是' : '否'}</dd></div>
            <div><dt>VNC 可用</dt><dd>{vncStatusQuery.data.vncAvailable ? '是' : '否'}</dd></div>
          </dl>
        ) : null}
        <VncViewer key={instanceId} instanceId={instanceId} available={vncStatusQuery.data?.vncAvailable === true} />
      </section>

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
