import type { BackendDateTime, BackendId, BackendLong } from '@/shared/api/contracts'

export const instanceLogSources = ['CHROME', 'XVFB', 'OPENBOX', 'X11VNC'] as const

export type InstanceLogSource = (typeof instanceLogSources)[number]
export type LogLevel = 'ALL' | 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

/** Mirrors HubLogContentDTO returned from the system and instance log endpoints. */
export interface HubLogContent {
  source: 'SYSTEM' | InstanceLogSource
  instanceId: BackendId | null
  content: string
  truncated: boolean
  fileSize: BackendLong
  modifiedAt: BackendDateTime
}

export type LogRequest =
  | { mode: 'SYSTEM'; lines: number }
  | { mode: 'INSTANCE'; instanceId: BackendId; source: InstanceLogSource; lines: number }
