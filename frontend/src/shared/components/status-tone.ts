export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

/** Known status mapping shared by instance and execution views. */
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
  STOPPING: 'warning',
  TIMEOUT: 'warning',
  TIMED_OUT: 'warning',
  ERROR: 'danger',
  FAILED: 'danger',
}

/** Resolve unknown statuses to a neutral tone so rendering remains safe. */
export function resolveStatusTone(status: string): StatusTone {
  return STATUS_TONES[status?.toUpperCase?.() ?? ''] ?? 'neutral'
}
