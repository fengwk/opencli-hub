import type { BackendDateTime } from '@/shared/api/contracts'

export type HubExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT'
export type SiteSessionMode = 'EPHEMERAL' | 'PERSISTENT'

export interface ExecutionResource {
  date: string
  group: string
  relativePath: string | null
  resourcePath: string
  fileName: string
  source: 'UPLOAD' | 'EXECUTION'
  mimeType: string
  size: number
  modifiedAt: BackendDateTime
  contentUrl: string
  downloadUrl: string
}

/** Mirrors HubExecutionDTO returned by the execution history endpoints. */
export interface HubExecution {
  id: number
  instanceId: number | null
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
  timeoutMillis: number
  queuedMillis: number
  durationMillis: number
  resources: ExecutionResource[] | null
  queuedAt: BackendDateTime
  startedAt: BackendDateTime
  finishedAt: BackendDateTime
}

export interface ExecutionListQuery {
  pageNumber: number
  pageSize: number
  instanceId?: number
}
