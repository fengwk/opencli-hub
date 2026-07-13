import type { HubInstance } from '@/features/instances/types'

export interface InstanceLifecycleActionsProps {
  instance: HubInstance
  busy: boolean
  onAction: (action: 'start' | 'stop' | 'restart') => void
}

/** Prevents lifecycle calls that the current state or queued work cannot satisfy. */
export function InstanceLifecycleActions({ instance, busy, onAction }: InstanceLifecycleActionsProps) {
  const queuedWork = (instance.runtime?.activeCount ?? 0) + (instance.runtime?.pendingCount ?? 0)
  const canStart = instance.state === 'STOPPED' || instance.state === 'ERROR'
  const canStopOrRestart = instance.state === 'RUNNING' && queuedWork === 0

  return (
    <div className="instance-actions" aria-label={`${instance.displayName} 生命周期操作`}>
      <button type="button" className="btn" disabled={busy || !canStart} onClick={() => onAction('start')}>启动</button>
      <button type="button" className="btn" disabled={busy || !canStopOrRestart} onClick={() => onAction('stop')}>停止</button>
      <button type="button" className="btn" disabled={busy || !canStopOrRestart} onClick={() => onAction('restart')}>重启</button>
    </div>
  )
}
