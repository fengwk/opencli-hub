import { Inbox } from 'lucide-react'
import type { ReactNode } from 'react'

export interface EmptyProps {
  title?: string
  description?: ReactNode
  action?: ReactNode
}

/** Generic empty-state placeholder. */
export function Empty({ title = '暂无数据', description, action }: EmptyProps) {
  return (
    <div className="state-block empty" role="note">
      <Inbox aria-hidden="true" className="state-icon" />
      <p className="state-title">{title}</p>
      {description ? <p className="state-desc">{description}</p> : null}
      {action ? <div className="state-action">{action}</div> : null}
    </div>
  )
}
