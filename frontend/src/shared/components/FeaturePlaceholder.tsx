import { Empty } from '@/shared/components/Empty'

export interface FeaturePlaceholderProps {
  title: string
  /** Optional context describing which module this shell belongs to. */
  hint?: string
}

/**
 * Explicit "feature not yet wired" placeholder for base-shell routes. It is a
 * deliberate empty state, not a stub for business data — feature agents replace
 * these pages with real implementations. No mock data is rendered.
 */
export function FeaturePlaceholder({ title, hint }: FeaturePlaceholderProps) {
  return (
    <section className="page">
      <header className="page-header">
        <h1 className="page-title">{title}</h1>
      </header>
      <Empty
        title="功能尚未接入"
        description={hint ?? `${title} 页面尚未实现，等待对应功能模块接入。`}
      />
    </section>
  )
}
