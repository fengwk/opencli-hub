import { useParams } from 'react-router-dom'
import { FeaturePlaceholder } from '@/shared/components'

export function ExecutionDetailPage() {
  const { id } = useParams<{ id: string }>()
  return (
    <FeaturePlaceholder
      title={`Execution ${id ?? ''}`.trim()}
      hint="Execution 详情尚未接入，等待 Executions 功能模块实现。"
    />
  )
}
