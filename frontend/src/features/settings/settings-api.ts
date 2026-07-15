import { apiClient } from '@/shared/api/client'
import type { HubSettings, SettingsEditableProperties } from '@/features/settings/types'

const settingsPath = '/settings'

type SettingsResponse = Partial<HubSettings>

function normalizeSettings(settings: SettingsResponse): HubSettings {
  if (settings.proxyMode === 'CUSTOM' && typeof settings.proxyServer === 'string') {
    return { proxyMode: 'CUSTOM', proxyServer: settings.proxyServer }
  }
  return { proxyMode: 'DIRECT', proxyServer: null }
}

export async function getSettings(): Promise<HubSettings> {
  return normalizeSettings(await apiClient.get<SettingsResponse>(settingsPath))
}

export async function updateSettings(properties: SettingsEditableProperties): Promise<HubSettings> {
  return normalizeSettings(await apiClient.put<SettingsResponse>(settingsPath, properties))
}
