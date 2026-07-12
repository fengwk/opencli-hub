import { useParams } from 'react-router-dom'
import { FeaturePlaceholder } from '@/shared/components'

export function InstanceDetailPage() {
  const { id } = useParams<{ id: string }>()
  return (
    <FeaturePlaceholder
      title={`Instance ${id ?? ''}`.trim()}
      hint="Instance 详情（含 VNC）尚未接入，等待 Instances 功能模块实现。"
    />
  )
}
