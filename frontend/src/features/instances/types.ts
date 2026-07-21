import type { BackendDateTime, BackendId } from '@/shared/api/contracts'

export type HubInstanceState = 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'ERROR'
export type InstanceProxyMode = 'INHERIT' | 'DIRECT' | 'CUSTOM'

export interface HubInstanceRuntime {
  registered: boolean
  displayNumber: number | null
  vncPort: number | null
  activeCount: number
  pendingCount: number
}

export interface HubInstance {
  id: BackendId
  code: string
  displayName: string
  contextId: string | null
  state: HubInstanceState
  websites: string[] | null
  maxPending: number
  priority: number
  proxyMode: InstanceProxyMode
  proxyServer: string | null
  lastErrorMessage: string | null
  stateChangedAt: BackendDateTime
  runtime: HubInstanceRuntime | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface InstanceEditableProperties {
  code: string
  displayName: string
  websites: string[]
  maxPending: number
  priority: number
  proxyMode: InstanceProxyMode
  proxyServer: string | null
}

export interface HubInstanceVncStatus {
  instanceId: BackendId
  instanceAvailable: boolean
  running: boolean
  runtimeAvailable: boolean
  vncAvailable: boolean
}
