import type { BackendId } from '@/shared/api/contracts'

export function buildVncWebSocketUrl(instanceId: BackendId): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/api/instances/${encodeURIComponent(instanceId)}/vnc`
}
