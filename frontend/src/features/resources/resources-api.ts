import { apiBaseUrl, apiClient } from '@/shared/api/client'
import { buildResourceUrl } from '@/shared/api/resource-url'
import type {
  ResourceDateSummary,
  ResourceFilters,
  ResourceItem,
  ResourceUploadResult,
} from '@/features/resources/types'

export function listResourceDates(): Promise<ResourceDateSummary[]> {
  return apiClient.get<ResourceDateSummary[]>('/resources/dates')
}

export function listResources(date: string, filters: ResourceFilters): Promise<ResourceItem[]> {
  return apiClient.get<ResourceItem[]>('/resources', {
    params: {
      date,
      ...(filters.source ? { source: filters.source } : {}),
      ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
      sort: filters.sort,
      page: 0,
      pageSize: 100,
    },
  })
}

export function uploadResources(date: string, files: File[]): Promise<ResourceUploadResult> {
  const body = new FormData()
  files.forEach((file) => body.append('files', file))
  const query = date ? `?${new URLSearchParams({ date }).toString()}` : ''
  return apiClient.post<ResourceUploadResult>(`/resources/uploads${query}`, body)
}

function toClientPath(url: string): string {
  return url.startsWith(apiBaseUrl) ? url.slice(apiBaseUrl.length) : url
}

export function deleteResource(item: Pick<ResourceItem, 'date' | 'group' | 'relativePath'>): Promise<void> {
  return apiClient.delete<void>(toClientPath(buildResourceUrl(item)))
}

export function deleteResourceGroup(date: string, group: string): Promise<void> {
  return apiClient.delete<void>(toClientPath(buildResourceUrl({ date, group })))
}

export function deleteResourceDate(date: string): Promise<void> {
  return apiClient.delete<void>(`/resources/${encodeURIComponent(date)}`)
}
