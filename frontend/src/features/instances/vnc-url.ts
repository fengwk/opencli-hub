export function buildVncWebSocketUrl(instanceId: number): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/api/instances/${encodeURIComponent(String(instanceId))}/vnc`
}
