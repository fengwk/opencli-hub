import type { BackendDateTime } from '@/shared/api/contracts'

export function formatMillis(value: number): string {
  return `${value} ms`
}

export function formatDateTime(value: BackendDateTime): string {
  if (value === null) return '—'
  if (typeof value === 'string') return value.replace('T', ' ')

  const [year, month, day, hour = 0, minute = 0, second = 0] = value
  if (year === undefined || month === undefined || day === undefined) return '—'
  const date = [year, month, day].map((part) => String(part).padStart(2, '0')).join('-')
  const time = [hour, minute, second].map((part) => String(part).padStart(2, '0')).join(':')
  return `${date} ${time}`
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
