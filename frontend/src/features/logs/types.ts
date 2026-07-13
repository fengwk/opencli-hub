import type { BackendDateTime } from '@/shared/api/contracts'

export const instanceLogSources = ['CHROME', 'XVFB', 'OPENBOX', 'X11VNC'] as const

export type InstanceLogSource = (typeof instanceLogSources)[number]
export type LogLevel = 'ALL' | 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

/** Mirrors HubLogContentDTO returned from the system and instance log endpoints. */
export interface HubLogContent {
  source: 'SYSTEM' | InstanceLogSource
  instanceId: number | null
  content: string
  truncated: boolean
  fileSize: number
  modifiedAt: BackendDateTime
}

export type LogRequest =
  | { mode: 'SYSTEM'; lines: number }
  | { mode: 'INSTANCE'; instanceId: number; source: InstanceLogSource; lines: number }
