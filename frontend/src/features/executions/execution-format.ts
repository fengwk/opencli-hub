import type { BackendLong } from '@/shared/api/contracts'
export { formatBackendDateTime as formatDateTime } from '@/shared/api/backend-date-time'

export function formatMillis(value: BackendLong): string {
  return `${value} ms`
}

/** Pretty-print JSON stdout without changing non-JSON process output. */
export function formatStdout(stdout: string | null): string {
  if (!stdout) return ''
  try {
    return JSON.stringify(JSON.parse(stdout), null, 2)
  } catch {
    return stdout
  }
}
