import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createPluginSource,
  deletePluginSource,
  listInstalledPlugins,
  listPluginSources,
  reloadPluginCatalog,
  syncPluginSource,
  updatePluginSource,
} from '@/features/plugins/plugins-api'
import type { HubPluginSource, HubPluginSourceUpsert } from '@/features/plugins/types'
import { Empty, ErrorState, Loading, StatusBadge } from '@/shared/components'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试。'
}

function parseDesiredPlugins(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function sourceStatusLabel(status: HubPluginSource['lastStatus']): string {
  const labels: Record<HubPluginSource['lastStatus'], string> = {
    IDLE: '尚未同步',
    SYNCING: '同步中',
    SUCCEEDED: '已同步',
    FAILED: '同步失败',
  }
  return labels[status]
}

function sourceSyncActionLabel(source: HubPluginSource): string {
  return (source.desiredPlugins ?? []).length ? '安装/更新已选子插件' : '安装默认集合'
}

function SourceForm({
  initial,
  busy,
  onSubmit,
  onCancel,
}: {
  initial?: HubPluginSource
  busy: boolean
  onSubmit: (payload: HubPluginSourceUpsert) => Promise<void>
  onCancel: () => void
}) {
  const [name, setName] = useState(initial?.name ?? '')
  const [source, setSource] = useState(initial?.source ?? '')
  const [desiredPluginsText, setDesiredPluginsText] = useState((initial?.desiredPlugins ?? []).join('\n'))
  const [enabled, setEnabled] = useState(initial?.enabled ?? true)
  const [validationError, setValidationError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim() || !source.trim()) {
      setValidationError('名称和 source 不能为空。')
      return
    }
    setValidationError(null)
    await onSubmit({
      name: name.trim(),
      source: source.trim(),
      desiredPlugins: parseDesiredPlugins(desiredPluginsText),
      enabled,
    })
  }

  return (
    <form className="settings-form plugin-source-form" noValidate onSubmit={(event) => void handleSubmit(event)}>
      <label>
        名称
        <input value={name} disabled={busy} onChange={(event) => setName(event.target.value)} />
      </label>
      <label>
        OpenCLI source
        <input
          value={source}
          disabled={busy}
          placeholder="github:org/repo 或 https://github.com/org/repo"
          onChange={(event) => setSource(event.target.value)}
        />
      </label>
      <label>
        子插件名（可选，每行一个；空表示安装源的默认集合）
        <textarea
          value={desiredPluginsText}
          disabled={busy}
          rows={4}
          onChange={(event) => setDesiredPluginsText(event.target.value)}
        />
      </label>
      <label className="plugin-source-toggle">
        <input type="checkbox" checked={enabled} disabled={busy} onChange={(event) => setEnabled(event.target.checked)} />
        <span>启用插件源</span>
      </label>
      <p className="plugin-source-form-help">保存只记录 source 配置；保存后手动执行 source 操作才会运行官方 OpenCLI plugin CLI。</p>
      {validationError ? <p className="page-error" role="alert">{validationError}</p> : null}
      <div className="logs-actions">
        <button type="submit" className="btn btn-primary" disabled={busy}>保存配置</button>
        <button type="button" className="btn" disabled={busy} onClick={onCancel}>取消</button>
      </div>
    </form>
  )
}

export function PluginsPage() {
  const queryClient = useQueryClient()
  const [editingId, setEditingId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const sourcesQuery = useQuery({
    queryKey: ['plugin-sources'],
    queryFn: listPluginSources,
  })
  const installedQuery = useQuery({
    queryKey: ['plugin-installed'],
    queryFn: listInstalledPlugins,
  })

  const createMutation = useMutation({
    mutationFn: createPluginSource,
    onSuccess: async () => {
      setCreating(false)
      setActionError(null)
      await queryClient.invalidateQueries({ queryKey: ['plugin-sources'] })
    },
    onError: (error) => setActionError(errorMessage(error)),
  })
  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string, payload: HubPluginSourceUpsert }) => updatePluginSource(id, payload),
    onSuccess: async () => {
      setEditingId(null)
      setActionError(null)
      await queryClient.invalidateQueries({ queryKey: ['plugin-sources'] })
    },
    onError: (error) => setActionError(errorMessage(error)),
  })
  const deleteMutation = useMutation({
    mutationFn: deletePluginSource,
    onSuccess: async () => {
      setActionError(null)
      await queryClient.invalidateQueries({ queryKey: ['plugin-sources'] })
    },
    onError: (error) => setActionError(errorMessage(error)),
  })
  const syncMutation = useMutation({
    mutationFn: syncPluginSource,
    onSuccess: async () => {
      setActionError(null)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['plugin-sources'] }),
        queryClient.invalidateQueries({ queryKey: ['plugin-installed'] }),
      ])
    },
    onError: (error) => setActionError(errorMessage(error)),
  })
  const reloadMutation = useMutation({
    mutationFn: reloadPluginCatalog,
    onSuccess: () => setActionError(null),
    onError: (error) => setActionError(errorMessage(error)),
  })

  const busy = createMutation.isPending || updateMutation.isPending || deleteMutation.isPending
    || syncMutation.isPending || reloadMutation.isPending

  const sources = useMemo(() => sourcesQuery.data ?? [], [sourcesQuery.data])

  return (
    <div className="page">
      <header className="page-header">
        <p className="eyebrow">MAINTENANCE</p>
        <h1 className="page-title">插件维护</h1>
        <p className="page-subtitle">
          保存 source 配置后，手动执行 source 操作，通过官方 `opencli plugin install/update/list` 同步。Hub 不执行后台自动更新。
        </p>
      </header>

      <div className="logs-actions" style={{ marginBottom: 16 }}>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => { setCreating(true); setEditingId(null) }}>
          新增插件源
        </button>
        <button type="button" className="btn" disabled={busy || reloadMutation.isPending} onClick={() => reloadMutation.mutate()}>
          刷新命令 Catalog
        </button>
        <button type="button" className="btn" disabled={installedQuery.isFetching} onClick={() => void installedQuery.refetch()}>
          刷新已安装列表
        </button>
      </div>

      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}
      {creating ? (
        <section className="execution-section">
          <h2>新增插件源</h2>
          <SourceForm
            busy={busy}
            onCancel={() => setCreating(false)}
            onSubmit={async (payload) => { await createMutation.mutateAsync(payload) }}
          />
        </section>
      ) : null}

      {sourcesQuery.isPending ? <Loading label="正在加载插件源…" /> : null}
      {sourcesQuery.isError ? (
        <ErrorState title="无法加载插件源" description={errorMessage(sourcesQuery.error)} onRetry={() => void sourcesQuery.refetch()} />
      ) : null}
      {sourcesQuery.isSuccess && sources.length === 0 ? (
        <Empty title="还没有插件源" description="添加 GitHub 插件源或 monorepo 子插件源后即可同步。" />
      ) : null}

      {sources.map((source) => (
        <section className="execution-section" key={source.id}>
          <div className="section-heading-row">
            <h2>{source.name}</h2>
            <StatusBadge status={source.lastStatus} label={sourceStatusLabel(source.lastStatus)} />
          </div>
          {editingId === source.id ? (
            <SourceForm
              initial={source}
              busy={busy}
              onCancel={() => setEditingId(null)}
              onSubmit={async (payload) => { await updateMutation.mutateAsync({ id: source.id, payload }) }}
            />
          ) : (
            <>
              <dl className="metadata-grid">
                <div><dt>Source</dt><dd className="mono-value">{source.source}</dd></div>
                <div><dt>子插件</dt><dd>{(source.desiredPlugins ?? []).length ? (source.desiredPlugins ?? []).join(', ') : '（默认集合）'}</dd></div>
                <div><dt>启用</dt><dd>{source.enabled ? '是' : '否'}</dd></div>
                <div><dt>最近错误</dt><dd>{source.lastError || '—'}</dd></div>
              </dl>
              {source.lastResult ? <pre className="execution-output">{source.lastResult}</pre> : null}
              <div className="logs-actions">
                <button type="button" className="btn btn-primary" disabled={busy || !source.enabled} onClick={() => syncMutation.mutate(source.id)}>
                  {sourceSyncActionLabel(source)}
                </button>
                <button type="button" className="btn" disabled={busy} onClick={() => { setEditingId(source.id); setCreating(false) }}>
                  编辑
                </button>
                <button type="button" className="btn" disabled={busy} onClick={() => deleteMutation.mutate(source.id)}>
                  删除配置
                </button>
              </div>
            </>
          )}
        </section>
      ))}

      <section className="execution-section">
        <h2>当前已安装插件（运行时）</h2>
        <p className="muted">此处只显示运行时实际安装的插件；仅保存 source 配置不会出现在列表中。</p>
        {installedQuery.isPending ? <Loading label="正在读取已安装插件…" /> : null}
        {installedQuery.isError ? (
          <ErrorState title="无法读取已安装插件" description={errorMessage(installedQuery.error)} onRetry={() => void installedQuery.refetch()} />
        ) : null}
        {installedQuery.isSuccess && (installedQuery.data?.length ?? 0) === 0 ? (
          <Empty title="尚未安装插件" description="同步插件源后会显示在这里。" />
        ) : null}
        {installedQuery.isSuccess && (installedQuery.data?.length ?? 0) > 0 ? (
          <ul className="resource-list">
            {installedQuery.data.map((plugin) => (
              <li className="resource-item" key={plugin.raw}>
                <div className="resource-item-details">
                  <strong>{plugin.name}</strong>
                  <span className="mono-value">{plugin.raw}</span>
                </div>
              </li>
            ))}
          </ul>
        ) : null}
      </section>
    </div>
  )
}
