import { AlertTriangle } from 'lucide-react'
import type { ReactNode } from 'react'

export interface ErrorStateProps {
  title?: string
  description?: ReactNode
  onRetry?: () => void
  retryLabel?: string
}

/** Generic error-state block with an optional retry action. */
export function ErrorState({
  title = '出错了',
  description,
  onRetry,
  retryLabel = '重试',
}: ErrorStateProps) {
  return (
    <div className="state-block error" role="alert">
      <AlertTriangle aria-hidden="true" className="state-icon" />
      <p className="state-title">{title}</p>
      {description ? <p className="state-desc">{description}</p> : null}
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          {retryLabel}
        </button>
      ) : null}
    </div>
  )
}
