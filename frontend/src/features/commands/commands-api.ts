import { apiClient } from '@/shared/api/client'
import type { HubCommand, OutputRuleUpdate } from '@/features/commands/types'

function commandPath(command: Pick<HubCommand, 'site' | 'name'>): string {
  return `/opencli/commands/${encodeURIComponent(command.site)}/${encodeURIComponent(command.name)}`
}

export function listCommands(website?: string): Promise<HubCommand[]> {
  return apiClient.get<HubCommand[]>('/opencli/commands', {
    params: website ? { website } : undefined,
  })
}

export function blacklistCommand(command: HubCommand, reason: string): Promise<HubCommand> {
  return apiClient.put<HubCommand>(`${commandPath(command)}/blacklist`, { reason: reason || null })
}

export function unblacklistCommand(command: HubCommand): Promise<HubCommand> {
  return apiClient.delete<HubCommand>(`${commandPath(command)}/blacklist`)
}

export function saveOutputRule(command: HubCommand, rule: OutputRuleUpdate): Promise<HubCommand> {
  return apiClient.put<HubCommand>(`${commandPath(command)}/output-rule`, rule)
}

export function deleteOutputRule(command: HubCommand): Promise<HubCommand> {
  return apiClient.delete<HubCommand>(`${commandPath(command)}/output-rule`)
}
