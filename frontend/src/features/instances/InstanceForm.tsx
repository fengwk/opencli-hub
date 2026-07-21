import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listCommands } from '@/features/commands/commands-api'
import type { InstanceEditableProperties, InstanceProxyMode } from '@/features/instances/types'
import { maximumProxyServerLength, validateCustomProxyServer } from '@/shared/proxy-validation'

export interface InstanceFormProps {
  initialValues?: InstanceEditableProperties
  submitLabel: string
  busy?: boolean
  autoFocus?: boolean
  onSubmit: (properties: InstanceEditableProperties) => Promise<void> | void
  onCancel?: () => void
}

const instanceCodePattern = /^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$/
const maximumCodeLength = 64
const maximumDisplayNameLength = 128
const minimumPendingCount = 1
const maximumPendingCount = 50

function catalogErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '无法加载网站目录。'
}

/** Shared create/edit form; sites come from the command catalog instead of a static list. */
export function InstanceForm({
  initialValues,
  submitLabel,
  busy = false,
  autoFocus = false,
  onSubmit,
  onCancel,
}: InstanceFormProps) {
  const [code, setCode] = useState(initialValues?.code ?? '')
  const [displayName, setDisplayName] = useState(initialValues?.displayName ?? '')
  const [websites, setWebsites] = useState<string[]>(initialValues?.websites ?? [])
  const [maxPending, setMaxPending] = useState(String(initialValues?.maxPending ?? 1))
  const [priority, setPriority] = useState(String(initialValues?.priority ?? 0))
  const [proxyMode, setProxyMode] = useState<InstanceProxyMode>(initialValues?.proxyMode ?? 'INHERIT')
  const [proxyServer, setProxyServer] = useState(initialValues?.proxyServer ?? '')
  const [websiteKeyword, setWebsiteKeyword] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const commandsQuery = useQuery({ queryKey: ['commands'], queryFn: () => listCommands() })
  const availableWebsites = useMemo(
    () => [...new Set([...(initialValues?.websites ?? []), ...(commandsQuery.data ?? []).map((command) => command.site)])].sort(),
    [commandsQuery.data, initialValues?.websites],
  )
  const visibleWebsites = useMemo(() => {
    const keyword = websiteKeyword.trim().toLocaleLowerCase()
    return keyword ? availableWebsites.filter((site) => site.toLocaleLowerCase().includes(keyword)) : availableWebsites
  }, [availableWebsites, websiteKeyword])

  function toggleWebsite(site: string) {
    setValidationError(null)
    setWebsites((current) => current.includes(site)
      ? current.filter((value) => value !== site)
      : [...current, site])
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedCode = code.trim()
    const normalizedDisplayName = displayName.trim()
    const parsedMaxPending = Number(maxPending)
    if (!instanceCodePattern.test(normalizedCode)) {
      setValidationError('实例代码须为 1 至 64 位小写字母、数字或连字符，且必须以字母或数字开头和结尾。')
      return
    }
    if (!normalizedDisplayName || normalizedDisplayName.length > maximumDisplayNameLength) {
      setValidationError('显示名称须为 1 至 128 个字符。')
      return
    }
    if (websites.length === 0) {
      setValidationError('请至少选择一个支持的网站。')
      return
    }
    if (!Number.isInteger(parsedMaxPending) || parsedMaxPending < minimumPendingCount || parsedMaxPending > maximumPendingCount) {
      setValidationError(`最大待处理数必须是 ${minimumPendingCount} 到 ${maximumPendingCount} 之间的整数。`)
      return
    }
    const parsedPriority = Number(priority)
    if (!Number.isInteger(parsedPriority) || parsedPriority < -1000 || parsedPriority > 1000) {
      setValidationError('优先级必须是 -1000 到 1000 之间的整数（越大越优先，默认 0）。')
      return
    }
    const normalizedProxyServer = proxyServer.trim()
    if (proxyMode === 'CUSTOM') {
      const proxyError = validateCustomProxyServer(normalizedProxyServer)
      if (proxyError) {
        setValidationError(proxyError)
        return
      }
    }
    setValidationError(null)
    await onSubmit({
      code: normalizedCode,
      displayName: normalizedDisplayName,
      websites,
      maxPending: parsedMaxPending,
      priority: parsedPriority,
      proxyMode,
      proxyServer: proxyMode === 'CUSTOM' ? normalizedProxyServer : null,
    })
  }

  return (
    <form className="instance-form" noValidate onSubmit={(event) => void handleSubmit(event)} aria-busy={busy}>
      <label>
        实例代码
        <input autoFocus={autoFocus} value={code} required maxLength={maximumCodeLength} disabled={busy} onChange={(event) => {
          setCode(event.target.value)
          setValidationError(null)
        }} />
      </label>
      <label>
        显示名称
        <input value={displayName} required maxLength={maximumDisplayNameLength} disabled={busy} onChange={(event) => {
          setDisplayName(event.target.value)
          setValidationError(null)
        }} />
      </label>
      <label>
        最大待处理数
        <input
          type="number"
          min={minimumPendingCount}
          max={maximumPendingCount}
          step="1"
          value={maxPending}
          required
          disabled={busy}
          onChange={(event) => {
            setMaxPending(event.target.value)
            setValidationError(null)
          }}
        />
      </label>
      <label>
        优先级
        <input
          type="number"
          min={-1000}
          max={1000}
          step="1"
          value={priority}
          required
          disabled={busy}
          title="自动路由时负载相同优先选更大值，默认 0"
          onChange={(event) => {
            setPriority(event.target.value)
            setValidationError(null)
          }}
        />
      </label>
      <label>
        代理模式
        <select
          value={proxyMode}
          disabled={busy}
          onChange={(event) => {
            setProxyMode(event.target.value as InstanceProxyMode)
            setValidationError(null)
          }}
        >
          <option value="INHERIT">继承全局</option>
          <option value="DIRECT">直连</option>
          <option value="CUSTOM">自定义代理</option>
        </select>
      </label>
      {proxyMode === 'CUSTOM' ? (
        <label>
          代理服务器
          <input
            type="text"
            value={proxyServer}
            required
            maxLength={maximumProxyServerLength}
            autoComplete="off"
            placeholder="socks5://proxy.example.com:1080"
            aria-describedby="instance-proxy-server-help"
            disabled={busy}
            onChange={(event) => {
              setProxyServer(event.target.value)
              setValidationError(null)
            }}
          />
        </label>
      ) : null}
      <p id="instance-proxy-server-help" className="form-help instance-proxy-help">代理仅控制浏览器访问网站的流量；容器 localhost 上的 OpenCLI 服务不会经过代理。代理地址按 Hub 容器网络解析。自定义代理仅支持不含用户名或密码的 http、https、socks4、socks5://host:port 地址。运行中的实例需手动重启后才会使用新配置。</p>
      <fieldset disabled={busy || commandsQuery.isPending}>
        <legend>支持的网站</legend>
        {commandsQuery.isPending ? <span className="muted">正在加载网站目录…</span> : null}
        {commandsQuery.isError ? <p className="inline-error" role="alert">{catalogErrorMessage(commandsQuery.error)}</p> : null}
        {commandsQuery.isSuccess && availableWebsites.length === 0 ? <span className="muted">命令目录中没有可选网站。</span> : null}
        {commandsQuery.isSuccess && availableWebsites.length > 0 ? (
          <div className="website-picker-toolbar">
            <label className="website-search">
              筛选网站
              <input value={websiteKeyword} placeholder="输入站点名称" onChange={(event) => setWebsiteKeyword(event.target.value)} />
            </label>
            <span>{websites.length} 已选择 · {availableWebsites.length} 可用</span>
          </div>
        ) : null}
        <div className="website-options">
          {visibleWebsites.map((site) => (
            <label key={site} className="checkbox-label">
              <input
                type="checkbox"
                checked={websites.includes(site)}
                onChange={() => toggleWebsite(site)}
              />
              <span>{site}</span>
            </label>
          ))}
          {commandsQuery.isSuccess && availableWebsites.length > 0 && visibleWebsites.length === 0 ? <span className="muted">没有匹配的网站。</span> : null}
        </div>
      </fieldset>
      {validationError ? <p className="inline-error" role="alert">{validationError}</p> : null}
      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={busy || commandsQuery.isPending}>
          {busy ? '正在保存…' : submitLabel}
        </button>
        {onCancel ? <button type="button" className="btn" disabled={busy} onClick={onCancel}>取消</button> : null}
      </div>
    </form>
  )
}
