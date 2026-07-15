export const maximumProxyServerLength = 512

const proxyServerPattern = /^(?:http|https|socks4|socks5):\/\/(\[[^\][/?#@]+\]|[^\s/?#:@]+):(\d{1,5})$/i
const dnsHostPattern = /^(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.?$/
const supportedProtocols = ['http:', 'https:', 'socks4:', 'socks5:']

function isJavaCompatibleNumericHost(host: string): boolean {
  const hasTrailingDot = host.endsWith('.')
  const address = hasTrailingDot ? host.slice(0, -1) : host
  const octets = address.split('.')
  if (octets.length === 1) return true
  return !hasTrailingDot && octets.length === 4 && octets.every((octet) => /^\d+$/.test(octet) && Number(octet) <= 255)
}

function isJavaCompatibleHost(host: string): boolean {
  if (!dnsHostPattern.test(host)) return false
  return !/^[0-9.]+$/.test(host) || isJavaCompatibleNumericHost(host)
}

/** Validates the unauthenticated proxy URL format accepted by the browser configuration API. */
export function validateCustomProxyServer(value: string): string | null {
  const proxyServer = value.trim()
  if (!proxyServer) return '请填写代理服务器地址。'
  if (proxyServer.length > maximumProxyServerLength) return `代理服务器地址不能超过 ${maximumProxyServerLength} 个字符。`

  let url: URL
  try {
    url = new URL(proxyServer)
  } catch {
    return '代理服务器须为 http、https、socks4 或 socks5 协议的 host:port 地址。'
  }

  if (!supportedProtocols.includes(url.protocol.toLowerCase())) {
    return '代理服务器仅支持 http、https、socks4 或 socks5 协议。'
  }
  if (url.username || url.password) {
    return '代理服务器不支持用户名或密码。'
  }
  if ((url.pathname !== '' && url.pathname !== '/') || url.search || url.hash) {
    return '代理服务器不能包含路径、查询参数或片段。'
  }

  // URL normalizes an explicit default port (for example :80) away, so retain a
  // syntax-level port check instead of relying on URL.port alone.
  const match = proxyServer.match(proxyServerPattern)
  if (!match || !url.hostname) {
    return '代理服务器须为 http://、https://、socks4:// 或 socks5://host:port，且不能包含路径或凭据。'
  }

  const [, host, portText] = match
  if (!host.startsWith('[') && !isJavaCompatibleHost(host)) {
    return '代理服务器 host 须为 Java URI 可接受的 ASCII DNS 或 IPv4 地址。'
  }

  const port = Number(portText)
  if (port < 1 || port > 65535) return '代理服务器端口须为 1 到 65535 之间的整数。'
  return null
}
