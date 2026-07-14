import { apiBaseUrl, apiClient } from '@/shared/api/client'
import { parseBackendId } from '@/shared/api/backend-id'
import type { BackendId } from '@/shared/api/contracts'
import { instanceLogSources } from '@/features/logs/types'
import type { HubLogContent, InstanceLogSource, LogRequest } from '@/features/logs/types'

const minimumLines = 1
export const maximumLogLines = 5000

function requireLineCount(lines: number): number {
  if (!Number.isSafeInteger(lines) || lines < minimumLines || lines > maximumLogLines) {
    throw new RangeError(`日志行数必须在 ${minimumLines} 到 ${maximumLogLines} 之间。`)
  }
  return lines
}

function requireInstanceId(instanceId: BackendId): BackendId {
  const normalized = parseBackendId(instanceId)
  if (!normalized) {
    throw new RangeError('Instance ID 不能为空。')
  }
  return normalized
}

function requireInstanceLogSource(source: string): InstanceLogSource {
  if (!(instanceLogSources as readonly string[]).includes(source)) {
    throw new RangeError('不支持的日志来源。')
  }
  return source as InstanceLogSource
}

export function getSystemLogs(lines: number): Promise<HubLogContent> {
  return apiClient.get<HubLogContent>('/logs/system', { params: { lines: requireLineCount(lines) } })
}

export function getInstanceLogs(instanceId: BackendId, source: InstanceLogSource, lines: number): Promise<HubLogContent> {
  const safeInstanceId = requireInstanceId(instanceId)
  return apiClient.get<HubLogContent>(`/instances/${encodeURIComponent(safeInstanceId)}/logs`, {
    params: { source: requireInstanceLogSource(source), lines: requireLineCount(lines) },
  })
}

export function getLogs(request: LogRequest): Promise<HubLogContent> {
  return request.mode === 'SYSTEM'
    ? getSystemLogs(request.lines)
    : getInstanceLogs(request.instanceId, request.source, request.lines)
}

/** Builds only the two fixed raw-download endpoints supported by the backend. */
export function getLogDownloadUrl(request: LogRequest): string {
  if (request.mode === 'SYSTEM') return `${apiBaseUrl}/logs/system/download`

  const instanceId = requireInstanceId(request.instanceId)
  const source = requireInstanceLogSource(request.source)
  return `${apiBaseUrl}/instances/${encodeURIComponent(instanceId)}/logs/download?${new URLSearchParams({ source }).toString()}`
}
