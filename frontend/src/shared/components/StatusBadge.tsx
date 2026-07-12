import { resolveStatusTone } from '@/shared/components/status-tone'
import type { StatusTone } from '@/shared/components/status-tone'

export interface StatusBadgeProps {
  status: string
  tone?: StatusTone
  label?: string
}

/** Presentational status badge with tone-based styling. */
export function StatusBadge({ status, tone, label }: StatusBadgeProps) {
  const resolvedTone = tone ?? resolveStatusTone(status)
  return (
    <span className={`badge badge-${resolvedTone}`} data-tone={resolvedTone}>
      {label ?? status}
    </span>
  )
}
