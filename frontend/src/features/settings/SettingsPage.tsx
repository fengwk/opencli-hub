import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getSettings, updateSettings } from '@/features/settings/settings-api'
import type { GlobalProxyMode, HubSettings, SettingsEditableProperties } from '@/features/settings/types'
import { ErrorState, Loading } from '@/shared/components'
import { maximumProxyServerLength, validateCustomProxyServer } from '@/shared/proxy-validation'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '保存失败，请稍后重试。'
}

function SettingsForm({
  initialValues,
  busy,
  onSubmit,
  onEdit,
}: {
  initialValues: HubSettings
  busy: boolean
  onSubmit: (properties: SettingsEditableProperties) => Promise<void>
  onEdit: () => void
}) {
  const [proxyMode, setProxyMode] = useState<GlobalProxyMode>(initialValues.proxyMode)
  const [proxyServer, setProxyServer] = useState(initialValues.proxyServer ?? '')
  const [validationError, setValidationError] = useState<string | null>(null)

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedProxyServer = proxyServer.trim()
    if (proxyMode === 'CUSTOM') {
      const error = validateCustomProxyServer(normalizedProxyServer)
      if (error) {
        setValidationError(error)
        return
      }
    }

    setValidationError(null)
    await onSubmit({
      proxyMode,
      proxyServer: proxyMode === 'CUSTOM' ? normalizedProxyServer : null,
    })
  }

  return (
    <form className="settings-form" noValidate onSubmit={(event) => void handleSubmit(event)} aria-busy={busy}>
      <label>
        全局代理模式
        <select
          value={proxyMode}
          disabled={busy}
          onChange={(event) => {
            setProxyMode(event.target.value as GlobalProxyMode)
            setValidationError(null)
            onEdit()
          }}
        >
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
            aria-describedby="proxy-server-help"
            disabled={busy}
            onChange={(event) => {
              setProxyServer(event.target.value)
              setValidationError(null)
              onEdit()
            }}
          />
        </label>
      ) : null}

      <p id="proxy-server-help" className="form-help">自定义代理仅支持不含用户名或密码的 http、https、socks4、socks5://host:port 地址，最长 512 个字符。</p>
      {validationError ? <p className="inline-error" role="alert">{validationError}</p> : null}
      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? '正在保存…' : '保存设置'}</button>
      </div>
    </form>
  )
}

export function SettingsPage() {
  const queryClient = useQueryClient()
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveFeedback, setSaveFeedback] = useState<string | null>(null)
  const settingsQuery = useQuery({ queryKey: ['settings'], queryFn: getSettings })
  const settingsMutation = useMutation({ mutationFn: updateSettings })

  async function save(properties: SettingsEditableProperties) {
    setSaveError(null)
    setSaveFeedback(null)
    try {
      const savedSettings = await settingsMutation.mutateAsync(properties)
      queryClient.setQueryData(['settings'], savedSettings)
      setSaveFeedback('系统设置已保存。运行中的实例需要手动重启后才会使用新配置。')
    } catch (error) {
      setSaveError(errorMessage(error))
    }
  }

  return (
    <div className="page settings-page">
      <header className="page-header detail-hero settings-hero">
        <div>
          <p className="eyebrow">SYSTEM · SETTINGS</p>
          <h1 className="page-title">系统设置</h1>
          <p className="page-subtitle">配置浏览器访问网站时使用的全局网络出口。</p>
        </div>
      </header>

      <section className="settings-layout" aria-labelledby="proxy-settings-title">
        <div className="settings-card">
          <p className="eyebrow">BROWSER NETWORK</p>
          <h2 id="proxy-settings-title">全局浏览器代理</h2>
          <p className="card-description">全局设置适用于选择“继承全局”的实例。实例也可以单独配置直连或自定义代理。</p>
          {settingsQuery.isPending ? <Loading label="正在加载系统设置…" /> : null}
          {settingsQuery.isError ? <ErrorState title="无法加载系统设置" description={errorMessage(settingsQuery.error)} onRetry={() => void settingsQuery.refetch()} /> : null}
          {settingsQuery.isSuccess ? (
            <SettingsForm
              key={`${settingsQuery.data.proxyMode}:${settingsQuery.data.proxyServer ?? ''}`}
              initialValues={settingsQuery.data}
              busy={settingsMutation.isPending}
              onSubmit={save}
              onEdit={() => {
                setSaveError(null)
                setSaveFeedback(null)
              }}
            />
          ) : null}
          {saveError ? <p className="page-error" role="alert">{saveError}</p> : null}
          {saveFeedback ? <p className="save-feedback" role="status">{saveFeedback}</p> : null}
        </div>

        <aside className="settings-notice" aria-label="代理生效范围说明">
          <p className="eyebrow">EFFECT SCOPE</p>
          <h2>生效说明</h2>
          <ul>
            <li>代理只控制浏览器访问网站的流量。</li>
            <li>容器 localhost 上的 OpenCLI 服务不会经过代理。</li>
            <li>代理地址按 Hub 容器网络解析；bridge 模式下 127.0.0.1 指向容器自身。</li>
            <li>保存配置不会重启 Chrome；正在运行的实例必须手动重启后才能生效。</li>
          </ul>
        </aside>
      </section>
    </div>
  )
}
