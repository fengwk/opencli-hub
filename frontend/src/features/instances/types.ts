export type HubInstanceState = 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'ERROR'

export interface HubInstanceRuntime {
  registered: boolean
  displayNumber: number | null
  vncPort: number | null
  activeCount: number
  pendingCount: number
}

export interface HubInstance {
  id: number
  code: string
  displayName: string
  contextId: string | null
  state: HubInstanceState
  websites: string[] | null
  maxPending: number
  lastErrorMessage: string | null
  stateChangedAt: string | number[] | null
  runtime: HubInstanceRuntime | null
  createTime: string | number[] | null
  updateTime: string | number[] | null
}

export interface InstanceEditableProperties {
  code: string
  displayName: string
  websites: string[]
  maxPending: number
}

export interface HubInstanceVncStatus {
  instanceId: number
  instanceAvailable: boolean
  running: boolean
  runtimeAvailable: boolean
  vncAvailable: boolean
}
