import { useEffect, useLayoutEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { buildResourceUrl } from '@/shared/api/resource-url'
import type { ResourceItem } from '@/features/resources/types'

interface ResourcePreviewProps {
  item: ResourceItem | null
  onClose: () => void
}

export function ResourcePreview({ item, onClose }: ResourcePreviewProps) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)

  useLayoutEffect(() => {
    if (!item) return
    const returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()
    return () => {
      document.body.style.overflow = previousOverflow
      if (returnFocus?.isConnected) returnFocus.focus()
    }
  }, [item])

  useEffect(() => {
    if (!item) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab') return

      const dialog = dialogRef.current
      if (!dialog) return
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), video[controls], iframe, [tabindex]:not([tabindex="-1"])',
      ))
      if (focusable.length === 0) {
        event.preventDefault()
        dialog.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (!dialog.contains(document.activeElement)) {
        event.preventDefault()
        const target = event.shiftKey ? last : first
        target.focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [item, onClose])

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
      <dialog
        ref={dialogRef}
        open
        className="dialog resource-preview-dialog"
        aria-label={`${item.fileName} 预览`}
        aria-modal="true"
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="section-heading-row">
          <h2 className="dialog-title">预览：{item.fileName}</h2>
          <button ref={closeRef} type="button" className="btn" onClick={onClose}>关闭</button>
        </div>
        {preview}
      </dialog>
    </div>
  )
}
