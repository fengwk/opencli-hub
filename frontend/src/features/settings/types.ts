export type GlobalProxyMode = 'DIRECT' | 'CUSTOM'

export interface HubSettings {
  proxyMode: GlobalProxyMode
  proxyServer: string | null
}

export type SettingsEditableProperties = HubSettings
