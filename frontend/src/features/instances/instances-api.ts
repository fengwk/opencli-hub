import { apiClient } from '@/shared/api/client'
import type { BackendId } from '@/shared/api/contracts'
import type { HubInstance, HubInstanceVncStatus, InstanceEditableProperties } from '@/features/instances/types'

const instancesPath = '/instances'

function instancePath(id: BackendId): string {
  return `${instancesPath}/${encodeURIComponent(id)}`
}

export function listInstances(): Promise<HubInstance[]> {
  return apiClient.get<HubInstance[]>(instancesPath)
}

export function getInstance(id: BackendId): Promise<HubInstance> {
  return apiClient.get<HubInstance>(instancePath(id))
}

export function createInstance(properties: InstanceEditableProperties): Promise<HubInstance> {
  return apiClient.post<HubInstance>(instancesPath, properties)
}

export function updateInstance(id: BackendId, properties: InstanceEditableProperties): Promise<HubInstance> {
  return apiClient.put<HubInstance>(instancePath(id), properties)
}

export function deleteInstance(id: BackendId): Promise<void> {
  return apiClient.delete<void>(instancePath(id))
}

export function runInstanceLifecycleAction(
  id: BackendId,
  action: 'start' | 'stop' | 'restart',
): Promise<HubInstance> {
  return apiClient.post<HubInstance>(`${instancePath(id)}/${action}`)
}

export function getInstanceVncStatus(id: BackendId): Promise<HubInstanceVncStatus> {
  return apiClient.get<HubInstanceVncStatus>(`${instancePath(id)}/vnc/status`)
}
