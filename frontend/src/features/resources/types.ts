import type { BackendDateTime, BackendLong } from '@/shared/api/contracts'

export type ResourceSource = 'UPLOAD' | 'EXECUTION'
export type ResourceSort = 'MODIFIED_DESC' | 'MODIFIED_ASC' | 'SIZE_DESC' | 'SIZE_ASC' | 'NAME_ASC' | 'NAME_DESC'

export interface ResourceDateSummary {
  date: string
  groupCount: BackendLong
  fileCount: BackendLong
  totalSize: BackendLong
}

export interface ResourceItem {
  date: string
  group: string
  relativePath: string
  resourcePath: string
  fileName: string
  source: ResourceSource
  mimeType: string
  size: BackendLong
  modifiedAt: BackendDateTime
  contentUrl: string
  downloadUrl: string
}

export interface ResourceUploadResult {
  date: string
  group: string
  items: ResourceItem[]
}

export interface ResourceFilters {
  source: ResourceSource | ''
  keyword: string
  sort: ResourceSort
}
