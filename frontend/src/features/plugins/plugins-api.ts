import { apiClient } from '@/shared/api/client'
import type {
  HubInstalledPlugin,
  HubPluginSource,
  HubPluginSourceUpsert,
} from '@/features/plugins/types'

export function listPluginSources(): Promise<HubPluginSource[]> {
  return apiClient.get<HubPluginSource[]>('/plugins/sources')
}

export function createPluginSource(request: HubPluginSourceUpsert): Promise<HubPluginSource> {
  return apiClient.post<HubPluginSource>('/plugins/sources', request)
}

export function updatePluginSource(id: string, request: HubPluginSourceUpsert): Promise<HubPluginSource> {
  return apiClient.put<HubPluginSource>(`/plugins/sources/${encodeURIComponent(id)}`, request)
}

export function deletePluginSource(id: string): Promise<void> {
  return apiClient.delete(`/plugins/sources/${encodeURIComponent(id)}`)
}

export function syncPluginSource(id: string): Promise<HubPluginSource> {
  // opencli plugin install/update may clone and npm-install for several minutes.
  return apiClient.post<HubPluginSource>(
    `/plugins/sources/${encodeURIComponent(id)}/sync`,
    undefined,
    { timeout: 320_000 },
  )
}

export function updateInstalledPluginSource(id: string): Promise<HubPluginSource> {
  // opencli plugin update may re-clone and npm-install for several minutes.
  return apiClient.post<HubPluginSource>(
    `/plugins/sources/${encodeURIComponent(id)}/update-installed`,
    undefined,
    { timeout: 320_000 },
  )
}

export function listInstalledPlugins(): Promise<HubInstalledPlugin[]> {
  return apiClient.get<HubInstalledPlugin[]>('/plugins/installed')
}

export function reloadPluginCatalog(): Promise<void> {
  return apiClient.post('/plugins/reload-catalog')
}
