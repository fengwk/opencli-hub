export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export interface StatusBadgeProps {
  status: string
  tone?: StatusTone
  label?: string
}

/**
 * Known status -> tone mapping shared across features. Covers documented
 * Instance states (RUNNING/STARTING/STOPPED/ERROR) and Execution statuses.
 * Unknown values fall back to a neutral tone so the badge is always safe to
 * render.
 */
const STATUS_TONES: Record<string, StatusTone> = {
  RUNNING: 'success',
  SUCCEEDED: 'success',
  SUCCESS: 'success',
  STARTING: 'info',
  QUEUED: 'info',
  PENDING: 'info',
  RUNNING_EXECUTION: 'info',
  STOPPED: 'neutral',
  IDLE: 'neutral',
  TIMEOUT: 'warning',
  ERROR: 'danger',
  FAILED: 'danger',
}

/** Resolve the tone for a status value; exported for testing and reuse. */
export function resolveStatusTone(status: string): StatusTone {
  return STATUS_TONES[status?.toUpperCase?.() ?? ''] ?? 'neutral'
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
