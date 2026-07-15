import { describe, expect, it } from 'vitest'
import { validateCustomProxyServer } from '@/shared/proxy-validation'

describe('validateCustomProxyServer', () => {
  it('accepts the backend-supported schemes, explicit default ports, and Java URI-compatible host forms', () => {
    expect(validateCustomProxyServer('HTTP://Proxy.Example:8080')).toBeNull()
    expect(validateCustomProxyServer('https://proxy.example:8443')).toBeNull()
    expect(validateCustomProxyServer('http://proxy.example.:80')).toBeNull()
    expect(validateCustomProxyServer('http://123.:8080')).toBeNull()
    expect(validateCustomProxyServer('socks4://01.02.03.04:1080')).toBeNull()
    expect(validateCustomProxyServer('socks5://[2001:db8::1]:1080')).toBeNull()
  })

  it('rejects hosts that browser URL accepts but Java URI rejects', () => {
    // Hub uses Java URI host parsing, so these must not pass browser-only URL validation.
    for (const host of ['代理.example', 'foo_bar', 'a..b', '-proxy.example', 'proxy-.example']) {
      expect(validateCustomProxyServer(`http://${host}:8080`)).toContain('Java URI')
    }
    expect(validateCustomProxyServer('http://999.999.999.999:8080')).not.toBeNull()
  })

  it('rejects credentials, missing or invalid ports, paths, query strings, fragments, and unsupported schemes', () => {
    expect(validateCustomProxyServer('http://user:pass@proxy.example:8080')).toContain('用户名或密码')
    expect(validateCustomProxyServer('http://proxy.example')).toContain('host:port')
    expect(validateCustomProxyServer('http://proxy.example:0')).toContain('1 到 65535')
    expect(validateCustomProxyServer('http://proxy.example:8080/path')).toContain('不能包含路径')
    expect(validateCustomProxyServer('http://proxy.example:8080?target=site')).toContain('不能包含路径')
    expect(validateCustomProxyServer('http://proxy.example:8080#fragment')).toContain('不能包含路径')
    expect(validateCustomProxyServer('ftp://proxy.example:21')).toContain('仅支持')
  })
})
