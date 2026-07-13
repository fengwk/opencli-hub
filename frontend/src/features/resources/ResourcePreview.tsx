import type { ReactNode } from 'react'
import { buildResourceUrl } from '@/shared/api/resource-url'
import type { ResourceItem } from '@/features/resources/types'

interface ResourcePreviewProps {
  item: ResourceItem | null
  onClose: () => void
}

export function ResourcePreview({ item, onClose }: ResourcePreviewProps) {
  if (!item) {
    return null
  }

  const contentUrl = buildResourceUrl(item, { inline: true })
  let preview: ReactNode
  if (item.mimeType.startsWith('image/')) {
    preview = <img className="resource-preview-image" src={contentUrl} alt={item.fileName} />
  } else if (item.mimeType.startsWith('video/')) {
    preview = <video className="resource-preview-video" src={contentUrl} controls>浏览器不支持视频预览。</video>
  } else {
    preview = <iframe className="resource-preview-pdf" src={contentUrl} title={`${item.fileName} PDF 预览`} />
  }

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <dialog open className="dialog resource-preview-dialog" aria-label={`${item.fileName} 预览`} onClick={(event) => event.stopPropagation()}>
        <div className="section-heading-row">
          <h2 className="dialog-title">预览：{item.fileName}</h2>
          <button type="button" className="btn" onClick={onClose}>关闭</button>
        </div>
        {preview}
      </dialog>
    </div>
  )
}
