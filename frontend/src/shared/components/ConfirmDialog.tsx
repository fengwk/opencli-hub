import { useEffect } from 'react'
import type { ReactNode } from 'react'
import type { StatusTone } from '@/shared/components/status-tone'

export interface ConfirmDialogProps {
  open: boolean
  title: string
  description?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  tone?: Extract<StatusTone, 'danger' | 'info'>
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/**
 * Lightweight confirmation dialog built on the native `<dialog>` element used
 * in its non-modal (`open` attribute) form, which keeps it renderable and
 * assertable under jsdom while still exposing dialog semantics to assistive
 * technology. Escape cancels; the backdrop click cancels.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = '确认',
  cancelLabel = '取消',
  tone = 'info',
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) {
      return
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        onCancel()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, busy, onCancel])

  if (!open) {
    return null
  }

  return (
    <div className="dialog-backdrop" onClick={busy ? undefined : onCancel}>
      <dialog open className="dialog" onClick={(event) => event.stopPropagation()}>
        <h2 className="dialog-title">{title}</h2>
        {description ? <div className="dialog-body">{description}</div> : null}
        <div className="dialog-actions">
          <button type="button" className="btn" disabled={busy} onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`btn btn-${tone === 'danger' ? 'danger' : 'primary'}`}
            disabled={busy}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </dialog>
    </div>
  )
}
