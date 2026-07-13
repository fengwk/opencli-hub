export type ResourceSource = 'UPLOAD' | 'EXECUTION'
export type ResourceSort = 'MODIFIED_DESC' | 'MODIFIED_ASC' | 'SIZE_DESC' | 'SIZE_ASC' | 'NAME_ASC' | 'NAME_DESC'

export interface ResourceDateSummary {
  date: string
  groupCount: number
  fileCount: number
  totalSize: number
}

export interface ResourceItem {
  date: string
  group: string
  relativePath: string
  resourcePath: string
  fileName: string
  source: ResourceSource
  mimeType: string
  size: number
  modifiedAt: string
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
