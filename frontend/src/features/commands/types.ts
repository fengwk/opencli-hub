import type { BackendId } from '@/shared/api/contracts'

export type CommandAccess = 'READ' | 'WRITE'
export type SiteSessionMode = 'EPHEMERAL' | 'PERSISTENT'
export type OutputTargetType = 'DIRECTORY' | 'FILE'

export interface CommandArgument {
  name: string
  type: string
  required: boolean
  valueRequired: boolean
  positional: boolean
  choices: string[] | null
  defaultValue: unknown
  help: string | null
}

export interface CommandOutputRule {
  id: BackendId
  commandKey: string
  argumentName: string
  targetType: OutputTargetType
  fileName: string | null
  createTime: string
  updateTime: string
}

export interface HubCommand {
  commandKey: string
  site: string
  name: string
  aliases: string[] | null
  description: string | null
  access: CommandAccess
  browser: boolean
  args: CommandArgument[] | null
  siteSession: SiteSessionMode | null
  defaultWindowMode: string | null
  blacklisted: boolean
  blacklistReason: string | null
  outputRule: CommandOutputRule | null
}

export interface OutputRuleUpdate {
  argumentName: string
  targetType: OutputTargetType
  fileName: string | null
}
