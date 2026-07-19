import type { BackendDateTime, BackendLong } from '@/shared/api/contracts'

export type HubPluginSourceStatus = 'IDLE' | 'SYNCING' | 'SUCCEEDED' | 'FAILED'

export interface HubPluginSource {
  id: string
  name: string
  source: string
  desiredPlugins: string[] | null
  enabled: boolean
  autoUpdate: boolean
  lastStatus: HubPluginSourceStatus
  lastError: string | null
  lastSyncedAt: BackendDateTime
  lastResult: string | null
  version: BackendLong
}

export interface HubPluginSourceUpsert {
  name: string
  source: string
  desiredPlugins: string[]
  enabled: boolean
  autoUpdate: boolean
}

export interface HubInstalledPlugin {
  name: string
  raw: string
}
