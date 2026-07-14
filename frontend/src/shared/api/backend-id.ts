import type { BackendId } from '@/shared/api/contracts'

/** Normalize a non-empty backend ID without interpreting its format or numeric value. */
export function parseBackendId(value: string): BackendId | undefined {
  const normalized = value.trim()
  return normalized || undefined
}
