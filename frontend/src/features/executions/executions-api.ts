import { apiClient } from '@/shared/api/client'
import type { PageResult } from '@/shared/api/contracts'
import type { ExecutionListQuery, HubExecution } from '@/features/executions/types'

/** Request the M6 execution history endpoint with its 1-based pagination contract. */
export function listExecutions(query: ExecutionListQuery): Promise<PageResult<HubExecution>> {
  return apiClient.get<PageResult<HubExecution>>('/executions', {
    params: {
      pageNumber: query.pageNumber,
      pageSize: query.pageSize,
      ...(query.instanceId !== undefined ? { instanceId: query.instanceId } : {}),
    },
  })
}

export function getExecution(id: string): Promise<HubExecution> {
  return apiClient.get<HubExecution>(`/executions/${encodeURIComponent(id)}`)
}
