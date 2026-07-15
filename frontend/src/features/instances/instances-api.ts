import { apiClient } from '@/shared/api/client'
import type { BackendId } from '@/shared/api/contracts'
import type { HubInstance, HubInstanceVncStatus, InstanceEditableProperties, InstanceProxyMode } from '@/features/instances/types'

const instancesPath = '/instances'

type HubInstanceResponse = Omit<HubInstance, 'proxyMode' | 'proxyServer'> & Partial<Pick<HubInstance, 'proxyMode' | 'proxyServer'>>

function instancePath(id: BackendId): string {
  return `${instancesPath}/${encodeURIComponent(id)}`
}

function isInstanceProxyMode(value: unknown): value is InstanceProxyMode {
  return value === 'INHERIT' || value === 'DIRECT' || value === 'CUSTOM'
}

function normalizeInstance(instance: HubInstanceResponse): HubInstance {
  const proxyMode = isInstanceProxyMode(instance.proxyMode) ? instance.proxyMode : 'INHERIT'
  return {
    ...instance,
    proxyMode,
    proxyServer: proxyMode === 'CUSTOM' && typeof instance.proxyServer === 'string' ? instance.proxyServer : null,
  }
}

export async function listInstances(): Promise<HubInstance[]> {
  return (await apiClient.get<HubInstanceResponse[]>(instancesPath)).map(normalizeInstance)
}

export async function getInstance(id: BackendId): Promise<HubInstance> {
  return normalizeInstance(await apiClient.get<HubInstanceResponse>(instancePath(id)))
}

export async function createInstance(properties: InstanceEditableProperties): Promise<HubInstance> {
  return normalizeInstance(await apiClient.post<HubInstanceResponse>(instancesPath, properties))
}

export async function updateInstance(id: BackendId, properties: InstanceEditableProperties): Promise<HubInstance> {
  return normalizeInstance(await apiClient.put<HubInstanceResponse>(instancePath(id), properties))
}

export function deleteInstance(id: BackendId): Promise<void> {
  return apiClient.delete<void>(instancePath(id))
}

export async function runInstanceLifecycleAction(
  id: BackendId,
  action: 'start' | 'stop' | 'restart',
): Promise<HubInstance> {
  return normalizeInstance(await apiClient.post<HubInstanceResponse>(`${instancePath(id)}/${action}`))
}

export function getInstanceVncStatus(id: BackendId): Promise<HubInstanceVncStatus> {
  return apiClient.get<HubInstanceVncStatus>(`${instancePath(id)}/vnc/status`)
}
