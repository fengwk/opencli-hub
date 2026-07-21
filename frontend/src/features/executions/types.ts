import type { BackendDateTime, BackendId, BackendLong } from '@/shared/api/contracts'

export type HubExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'
export type SiteSessionMode = 'EPHEMERAL' | 'PERSISTENT'

export interface ExecutionResource {
  date: string
  group: string
  relativePath: string | null
  resourcePath: string
  fileName: string
  source: 'UPLOAD' | 'EXECUTION'
  mimeType: string
  size: BackendLong
  modifiedAt: BackendDateTime
  contentUrl: string
  downloadUrl: string
}

/** Mirrors HubExecutionDTO returned by the execution history endpoints. */
export interface HubExecution {
  id: BackendId
  instanceId: BackendId | null
  instanceCode: string | null
  commandKey: string | null
  site: string | null
  siteSession: SiteSessionMode | null
  reuseInstance: boolean
  argv: string[] | null
  status: HubExecutionStatus
  exitCode: number | null
  stdout: string | null
  stdoutTruncated: boolean
  stderr: string | null
  stderrTruncated: boolean
  errorMessage: string | null
  timeoutMillis: BackendLong
  queuedMillis: BackendLong
  durationMillis: BackendLong
  resources: ExecutionResource[] | null
  queuedAt: BackendDateTime
  startedAt?: BackendDateTime
  finishedAt?: BackendDateTime
}

export interface ExecutionListQuery {
  pageNumber: number
  pageSize: number
  instanceId?: BackendId
}
