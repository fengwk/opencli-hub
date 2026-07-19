import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ResourcePreview } from '@/features/resources/ResourcePreview'
import { canPreview } from '@/features/resources/preview-utils'
import {
  deleteResource,
  deleteResourceDate,
  deleteResourceGroup,
  listResourceDates,
  listResources,
  uploadResources,
} from '@/features/resources/resources-api'
import type { ResourceFilters, ResourceItem } from '@/features/resources/types'
import { formatBackendDateTime } from '@/shared/api/backend-date-time'
import type { BackendLong } from '@/shared/api/contracts'
import { ConfirmDialog, Empty, ErrorState, Loading, StatusBadge } from '@/shared/components'
import { buildResourceUrl } from '@/shared/api/resource-url'

type DeleteTarget =
  | { kind: 'resource', item: ResourceItem }
  | { kind: 'group', date: string, group: string }
  | { kind: 'date', date: string }

const defaultFilters: ResourceFilters = {
  source: '',
  keyword: '',
  sort: 'MODIFIED_DESC',
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function formatSize(size: BackendLong): string {
  const numericSize = typeof size === 'number' ? size : Number(size)
  if (!Number.isFinite(numericSize) || numericSize < 0) return '—'
  if (numericSize < 1024) return `${numericSize} B`
  if (numericSize < 1024 * 1024) return `${(numericSize / 1024).toFixed(1)} KB`
  if (numericSize < 1024 * 1024 * 1024) return `${(numericSize / (1024 * 1024)).toFixed(1)} MB`
  return `${(numericSize / (1024 * 1024 * 1024)).toFixed(1)} GB`
}

function deleteDescription(target: DeleteTarget): string {
  if (target.kind === 'resource') return `将永久删除文件“${target.item.fileName}”。`
  if (target.kind === 'group') return `将永久删除资源组“${target.group}”及其中所有文件。`
  return `将永久删除 ${target.date} 的全部资源。`
}

export function ResourcesPage() {
  const queryClient = useQueryClient()
  const [selectedDate, setSelectedDate] = useState('')
  const [uploadDate, setUploadDate] = useState('')
  const [filters, setFilters] = useState<ResourceFilters>(defaultFilters)
  const [files, setFiles] = useState<File[]>([])
  const [previewItem, setPreviewItem] = useState<ResourceItem | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const datesQuery = useQuery({ queryKey: ['resource-dates'], queryFn: listResourceDates })
  const resourcesQuery = useQuery({
    queryKey: ['resources', selectedDate, filters],
    queryFn: () => listResources(selectedDate, filters),
    enabled: Boolean(selectedDate),
  })

  useEffect(() => {
    if (!datesQuery.data) return
    const selectedDateExists = datesQuery.data.some((summary) => summary.date === selectedDate)
    if (!selectedDateExists) {
      const nextDate = datesQuery.data[0]?.date ?? ''
      setSelectedDate(nextDate)
      if (!uploadDate || uploadDate === selectedDate) {
        setUploadDate(nextDate)
      }
    }
  }, [datesQuery.data, selectedDate, uploadDate])

  const groupedResources = useMemo(() => {
    const groups = new Map<string, ResourceItem[]>()
    for (const item of resourcesQuery.data ?? []) {
      const items = groups.get(item.group) ?? []
      items.push(item)
      groups.set(item.group, items)
    }
    return [...groups.entries()]
  }, [resourcesQuery.data])

  function selectDate(date: string) {
    setSelectedDate(date)
    setUploadDate(date)
    setPreviewItem(null)
    setActionError(null)
  }

  async function refreshResources() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['resource-dates'] }),
      queryClient.invalidateQueries({ queryKey: ['resources'] }),
    ])
  }

  async function submitUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!files.length) {
      setActionError('请选择至少一个文件。')
      return
    }
    setActionError(null)
    setActionPending(true)
    try {
      const result = await uploadResources(uploadDate, files)
      setFiles([])
      if (fileInputRef.current) fileInputRef.current.value = ''
      selectDate(result.date)
      await refreshResources()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    setActionError(null)
    setActionPending(true)
    try {
      if (deleteTarget.kind === 'resource') {
        await deleteResource(deleteTarget.item)
      } else if (deleteTarget.kind === 'group') {
        await deleteResourceGroup(deleteTarget.date, deleteTarget.group)
      } else {
        await deleteResourceDate(deleteTarget.date)
        if (selectedDate === deleteTarget.date) {
          setPreviewItem(null)
        }
      }
      setDeleteTarget(null)
      await refreshResources()
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <p className="eyebrow">RESOURCE ARCHIVE</p>
        <h1 className="page-title">资源中心</h1>
        <p className="page-subtitle">按 UTC 日期浏览上传和命令执行产生的资源。</p>
      </header>

      <form className="upload-form" onSubmit={(event) => void submitUpload(event)}>
        <label>
          上传日期（可选）
          <input type="date" value={uploadDate} disabled={actionPending} onChange={(event) => setUploadDate(event.target.value)} />
        </label>
        <label>
          选择文件
          <input ref={fileInputRef} type="file" multiple disabled={actionPending} onChange={(event) => setFiles(Array.from(event.target.files ?? []))} />
        </label>
        <button type="submit" className="btn btn-primary" disabled={actionPending}>上传文件</button>
      </form>

      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}
      {datesQuery.isPending ? <Loading label="正在加载资源日期…" /> : null}
      {datesQuery.isError ? <ErrorState title="无法加载资源日期" description={errorMessage(datesQuery.error)} onRetry={() => void datesQuery.refetch()} /> : null}
      {datesQuery.isSuccess && datesQuery.data.length === 0 ? <Empty title="暂无资源" description="上传文件或执行产生资源后，会在这里按日期归档。" /> : null}
      {datesQuery.isSuccess && datesQuery.data.length > 0 ? (
        <section className="resource-layout">
          <aside className="date-list" aria-label="资源日期">
            <h2>日期</h2>
            {datesQuery.data.map((summary) => (
              <div className={`date-summary ${summary.date === selectedDate ? 'selected' : ''}`} key={summary.date}>
                <button type="button" className="date-select" aria-pressed={summary.date === selectedDate} onClick={() => selectDate(summary.date)}>
                  <strong>{summary.date}</strong>
                  <span>{summary.groupCount} 组 · {summary.fileCount} 文件 · {formatSize(summary.totalSize)}</span>
                </button>
                <button type="button" className="btn btn-danger compact-button" aria-label={`删除日期 ${summary.date}`} disabled={actionPending} onClick={() => setDeleteTarget({ kind: 'date', date: summary.date })}>删除</button>
              </div>
            ))}
          </aside>

          <section className="resource-content" aria-label="资源文件">
            {!selectedDate ? <Empty title="暂无可浏览资源" description="上传文件后会在这里显示资源内容。" /> : null}
            {selectedDate ? <div className="filter-bar">
              <label>
                关键词
                <input value={filters.keyword} onChange={(event) => setFilters((current) => ({ ...current, keyword: event.target.value }))} />
              </label>
              <label>
                来源
                <select value={filters.source} onChange={(event) => setFilters((current) => ({ ...current, source: event.target.value as ResourceFilters['source'] }))}>
                  <option value="">全部</option>
                  <option value="UPLOAD">UPLOAD</option>
                  <option value="EXECUTION">EXECUTION</option>
                </select>
              </label>
              <label>
                排序
                <select value={filters.sort} onChange={(event) => setFilters((current) => ({ ...current, sort: event.target.value as ResourceFilters['sort'] }))}>
                  <option value="MODIFIED_DESC">修改时间（新到旧）</option>
                  <option value="MODIFIED_ASC">修改时间（旧到新）</option>
                  <option value="SIZE_DESC">大小（大到小）</option>
                  <option value="SIZE_ASC">大小（小到大）</option>
                  <option value="NAME_ASC">文件名（A-Z）</option>
                  <option value="NAME_DESC">文件名（Z-A）</option>
                </select>
              </label>
            </div> : null}

            {selectedDate && resourcesQuery.isPending ? <Loading label="正在加载当天资源…" /> : null}
            {selectedDate && resourcesQuery.isError ? <ErrorState title="无法加载当天资源" description={errorMessage(resourcesQuery.error)} onRetry={() => void resourcesQuery.refetch()} /> : null}
            {selectedDate && resourcesQuery.isSuccess && groupedResources.length === 0 ? <Empty title="当天没有匹配的资源" /> : null}
            {selectedDate && resourcesQuery.isSuccess && groupedResources.map(([group, items]) => (
              <section className="resource-group" key={group}>
                <div className="section-heading-row">
                  <h2>{group}</h2>
                  <button type="button" className="btn btn-danger" disabled={actionPending} onClick={() => setDeleteTarget({ kind: 'group', date: selectedDate, group })}>删除资源组</button>
                </div>
                <ul className="resource-list">
                  {items.map((item) => {
                    const downloadUrl = buildResourceUrl(item)
                    return (
                      <li className="resource-item" key={item.resourcePath}>
                        <div className="resource-item-details">
                          <strong>{item.relativePath || item.fileName}</strong>
                          <span>{item.mimeType} · {formatSize(item.size)} · {formatBackendDateTime(item.modifiedAt)}</span>
                        </div>
                        <StatusBadge status={item.source} tone={item.source === 'UPLOAD' ? 'info' : 'success'} />
                        <div className="resource-actions">
                          {canPreview(item) ? <button type="button" className="btn" onClick={() => setPreviewItem(item)}>预览</button> : null}
                          <a className="btn" href={downloadUrl}>下载</a>
                          <button type="button" className="btn btn-danger" disabled={actionPending} onClick={() => setDeleteTarget({ kind: 'resource', item })}>删除文件</button>
                        </div>
                      </li>
                    )
                  })}
                </ul>
              </section>
            ))}
          </section>
        </section>
      ) : null}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="确认删除资源？"
        description={deleteTarget ? deleteDescription(deleteTarget) : undefined}
        confirmLabel="删除"
        tone="danger"
        busy={actionPending}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => void confirmDelete()}
      />
      <ResourcePreview item={previewItem} onClose={() => setPreviewItem(null)} />
    </div>
  )
}
