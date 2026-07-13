import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listCommands } from '@/features/commands/commands-api'
import type { InstanceEditableProperties } from '@/features/instances/types'

export interface InstanceFormProps {
  initialValues?: InstanceEditableProperties
  submitLabel: string
  busy?: boolean
  onSubmit: (properties: InstanceEditableProperties) => Promise<void> | void
  onCancel?: () => void
}

function catalogErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '无法加载网站目录。'
}

/** Shared create/edit form; sites come from the command catalog instead of a static list. */
export function InstanceForm({
  initialValues,
  submitLabel,
  busy = false,
  onSubmit,
  onCancel,
}: InstanceFormProps) {
  const [code, setCode] = useState(initialValues?.code ?? '')
  const [displayName, setDisplayName] = useState(initialValues?.displayName ?? '')
  const [websites, setWebsites] = useState<string[]>(initialValues?.websites ?? [])
  const [maxPending, setMaxPending] = useState(String(initialValues?.maxPending ?? 1))
  const [validationError, setValidationError] = useState<string | null>(null)
  const commandsQuery = useQuery({ queryKey: ['commands'], queryFn: () => listCommands() })
  const availableWebsites = useMemo(
    () => [...new Set([...(initialValues?.websites ?? []), ...(commandsQuery.data ?? []).map((command) => command.site)])].sort(),
    [commandsQuery.data, initialValues?.websites],
  )

  function toggleWebsite(site: string) {
    setWebsites((current) => current.includes(site)
      ? current.filter((value) => value !== site)
      : [...current, site])
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedMaxPending = Number(maxPending)
    if (!Number.isInteger(parsedMaxPending) || parsedMaxPending < 0) {
      setValidationError('最大待处理数必须是大于或等于 0 的整数。')
      return
    }
    setValidationError(null)
    await onSubmit({ code: code.trim(), displayName: displayName.trim(), websites, maxPending: parsedMaxPending })
  }

  return (
    <form className="instance-form" onSubmit={(event) => void handleSubmit(event)} aria-busy={busy}>
      <label>
        实例代码
        <input value={code} required disabled={busy} onChange={(event) => setCode(event.target.value)} />
      </label>
      <label>
        显示名称
        <input value={displayName} required disabled={busy} onChange={(event) => setDisplayName(event.target.value)} />
      </label>
      <label>
        最大待处理数
        <input
          type="number"
          min="0"
          step="1"
          value={maxPending}
          required
          disabled={busy}
          onChange={(event) => setMaxPending(event.target.value)}
        />
      </label>
      <fieldset disabled={busy || commandsQuery.isPending}>
        <legend>支持的网站</legend>
        {commandsQuery.isPending ? <span className="muted">正在加载网站目录…</span> : null}
        {commandsQuery.isError ? <p className="inline-error" role="alert">{catalogErrorMessage(commandsQuery.error)}</p> : null}
        {commandsQuery.isSuccess && availableWebsites.length === 0 ? <span className="muted">命令目录中没有可选网站。</span> : null}
        <div className="website-options">
          {availableWebsites.map((site) => (
            <label key={site} className="checkbox-label">
              <input
                type="checkbox"
                checked={websites.includes(site)}
                onChange={() => toggleWebsite(site)}
              />
              {site}
            </label>
          ))}
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
