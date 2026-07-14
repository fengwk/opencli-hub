import { Play, RotateCw, Square } from 'lucide-react'
import type { HubInstance } from '@/features/instances/types'

export interface InstanceLifecycleActionsProps {
  instance: HubInstance
  busy: boolean
  compact?: boolean
  onAction: (action: 'start' | 'stop' | 'restart') => void
}

/** Prevents lifecycle calls that the current state or queued work cannot satisfy. */
export function InstanceLifecycleActions({ instance, busy, compact = false, onAction }: InstanceLifecycleActionsProps) {
  const queuedWork = (instance.runtime?.activeCount ?? 0) + (instance.runtime?.pendingCount ?? 0)
  const canStart = instance.state === 'STOPPED' || instance.state === 'ERROR'
  const canStopOrRestart = instance.state === 'RUNNING' && queuedWork === 0
  const buttonClassName = compact ? 'btn icon-button lifecycle-button' : 'btn'

  return (
    <div className="instance-actions" aria-label={`${instance.displayName} 生命周期操作`}>
      <button type="button" className={buttonClassName} aria-label="启动" title="启动" disabled={busy || !canStart} onClick={() => onAction('start')}>
        <Play aria-hidden="true" />{compact ? null : '启动'}
      </button>
      <button type="button" className={buttonClassName} aria-label="停止" title="停止" disabled={busy || !canStopOrRestart} onClick={() => onAction('stop')}>
        <Square aria-hidden="true" />{compact ? null : '停止'}
      </button>
      <button type="button" className={buttonClassName} aria-label="重启" title="重启" disabled={busy || !canStopOrRestart} onClick={() => onAction('restart')}>
        <RotateCw aria-hidden="true" />{compact ? null : '重启'}
      </button>
    </div>
  )
}
