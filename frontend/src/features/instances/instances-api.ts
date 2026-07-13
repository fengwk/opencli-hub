import { apiClient } from '@/shared/api/client'
import type { HubInstance, HubInstanceVncStatus, InstanceEditableProperties } from '@/features/instances/types'

const instancesPath = '/instances'

export function listInstances(): Promise<HubInstance[]> {
  return apiClient.get<HubInstance[]>(instancesPath)
}

export function getInstance(id: number): Promise<HubInstance> {
  return apiClient.get<HubInstance>(`${instancesPath}/${id}`)
}

export function createInstance(properties: InstanceEditableProperties): Promise<HubInstance> {
  return apiClient.post<HubInstance>(instancesPath, properties)
}

export function updateInstance(id: number, properties: InstanceEditableProperties): Promise<HubInstance> {
  return apiClient.put<HubInstance>(`${instancesPath}/${id}`, properties)
}

export function deleteInstance(id: number): Promise<void> {
  return apiClient.delete<void>(`${instancesPath}/${id}`)
}

export function runInstanceLifecycleAction(
  id: number,
  action: 'start' | 'stop' | 'restart',
): Promise<HubInstance> {
  return apiClient.post<HubInstance>(`${instancesPath}/${id}/${action}`)
}

export function getInstanceVncStatus(id: number): Promise<HubInstanceVncStatus> {
  return apiClient.get<HubInstanceVncStatus>(`${instancesPath}/${id}/vnc/status`)
}
