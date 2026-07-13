import type { ResourceItem } from '@/features/resources/types'

export function canPreview(item: ResourceItem): boolean {
  return item.mimeType.startsWith('image/')
    || item.mimeType.startsWith('video/')
    || item.mimeType === 'application/pdf'
}
