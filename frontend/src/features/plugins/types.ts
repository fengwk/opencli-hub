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
  lastSyncedAt: string | number[] | null
  lastResult: string | null
  version: number | string
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
