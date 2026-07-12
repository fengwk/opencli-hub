import { Loader2 } from 'lucide-react'

export interface LoadingProps {
  label?: string
}

/** Generic inline loading indicator with an accessible status role. */
export function Loading({ label = '加载中…' }: LoadingProps) {
  return (
    <div className="state-block" role="status" aria-live="polite">
      <Loader2 aria-hidden="true" className="spin" />
      <span>{label}</span>
    </div>
  )
}
