import type { BackendDateTime } from '@/shared/api/contracts'

/** Format the LocalDateTime wire forms emitted by the backend JSON mapper. */
export function formatBackendDateTime(value: BackendDateTime | undefined): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value.replace('T', ' ')
  if (!Array.isArray(value)) return '—'

  const [year, month, day, hour = 0, minute = 0, second = 0] = value
  const parts = [year, month, day, hour, minute, second]
  if (parts.some((part) => !Number.isInteger(part))) return '—'
  const date = [year, month, day].map((part) => String(part).padStart(2, '0')).join('-')
  const time = [hour, minute, second].map((part) => String(part).padStart(2, '0')).join(':')
  return `${date} ${time}`
}
