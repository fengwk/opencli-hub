import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listCommands } from '@/features/commands/commands-api'
import type { InstanceEditableProperties } from '@/features/instances/types'

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
    setValidationError(null)
    await onSubmit({ code: normalizedCode, displayName: normalizedDisplayName, websites, maxPending: parsedMaxPending })
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
