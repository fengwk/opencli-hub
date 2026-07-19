import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, UIEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getLogDownloadUrl, getLogs, maximumLogLines } from '@/features/logs/logs-api'
import { instanceLogSources } from '@/features/logs/types'
import type { HubLogContent, InstanceLogSource, LogLevel, LogRequest } from '@/features/logs/types'
import { formatBackendByteSize } from '@/shared/api/backend-byte-size'
import { formatBackendDateTime } from '@/shared/api/backend-date-time'
import { Empty, ErrorState, Loading, StatusBadge } from '@/shared/components'
import { parseBackendId } from '@/shared/api/backend-id'
import type { BackendId } from '@/shared/api/contracts'

const defaultLineCount = 500
const logLevels: LogLevel[] = ['ALL', 'TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']
/** Distance from the bottom under which we still treat the view as "pinned" for auto-scroll. */
const autoScrollBottomThresholdPx = 48

type LogMode = LogRequest['mode']

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function parseInstanceId(value: string): BackendId | undefined {
  return parseBackendId(value)
}

function parseLineCount(value: string): number | undefined {
  if (!/^[1-9]\d*$/.test(value.trim())) return undefined
  const lines = Number(value)
  return Number.isSafeInteger(lines) && lines <= maximumLogLines ? lines : undefined
}

function filterLogLines(content: string, level: LogLevel, keyword: string): string[] {
  const normalizedKeyword = keyword.trim().toLocaleLowerCase()
  const levelPattern = level === 'ALL' ? null : new RegExp(`\\b${level}\\b`, 'i')

  return content.split('\n').filter((line) => (
    (!levelPattern || levelPattern.test(line))
    && (!normalizedKeyword || line.toLocaleLowerCase().includes(normalizedKeyword))
  ))
}

function requestFor(mode: LogMode, instanceInput: string, source: InstanceLogSource, linesInput: string): LogRequest | undefined {
  const lines = parseLineCount(linesInput)
  if (lines === undefined) return undefined
  if (mode === 'SYSTEM') return { mode, lines }

  const instanceId = parseInstanceId(instanceInput)
  return instanceId === undefined ? undefined : { mode, instanceId, source, lines }
}

function distanceFromBottom(element: HTMLElement): number {
  return element.scrollHeight - element.scrollTop - element.clientHeight
}

function LogMetadata({ log }: { log: HubLogContent }) {
  return (
    <dl className="metadata-grid logs-metadata">
      <div><dt>来源</dt><dd>{log.source}</dd></div>
      <div><dt>Instance ID</dt><dd>{log.instanceId ?? '系统日志'}</dd></div>
      <div><dt>文件大小</dt><dd>{formatBackendByteSize(log.fileSize)}</dd></div>
      <div><dt>更新时间</dt><dd>{formatBackendDateTime(log.modifiedAt)}</dd></div>
      <div><dt>读取状态</dt><dd><StatusBadge status={log.truncated ? '已截断' : '完整'} tone={log.truncated ? 'warning' : 'success'} /></dd></div>
    </dl>
  )
}

export function LogsPage() {
  const [searchParams] = useSearchParams()
  const initialInstanceId = parseInstanceId(searchParams.get('instanceId') ?? '')
  const initialMode: LogMode = initialInstanceId === undefined ? 'SYSTEM' : 'INSTANCE'
  const initialInstanceInput = initialInstanceId ?? ''
  const initialSource: InstanceLogSource = 'CHROME'
  const initialRequest = requestFor(initialMode, initialInstanceInput, initialSource, String(defaultLineCount))

  const [mode, setMode] = useState<LogMode>(initialMode)
  const [instanceInput, setInstanceInput] = useState(initialInstanceInput)
  const [source, setSource] = useState<InstanceLogSource>(initialSource)
  const [linesInput, setLinesInput] = useState(String(defaultLineCount))
  const [activeRequest, setActiveRequest] = useState<LogRequest>(initialRequest ?? { mode: 'SYSTEM', lines: defaultLineCount })
  const [level, setLevel] = useState<LogLevel>('ALL')
  const [keyword, setKeyword] = useState('')
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [configurationError, setConfigurationError] = useState<string | null>(null)
  const [stickToBottom, setStickToBottom] = useState(true)
  const logOutputRef = useRef<HTMLPreElement | null>(null)

  const logsQuery = useQuery({
    queryKey: ['logs', activeRequest],
    queryFn: () => getLogs(activeRequest),
    refetchInterval: autoRefresh ? 5_000 : false,
    refetchIntervalInBackground: true,
  })

  const filteredLines = useMemo(
    () => filterLogLines(logsQuery.data?.content ?? '', level, keyword),
    [keyword, level, logsQuery.data?.content],
  )
  const hasLogContent = (logsQuery.data?.content.length ?? 0) > 0
  const logText = filteredLines.join('\n')

  useEffect(() => {
    const element = logOutputRef.current
    if (!element || !stickToBottom) return
    element.scrollTop = element.scrollHeight
  }, [logText, stickToBottom, logsQuery.dataUpdatedAt])

  function handleLogScroll(event: UIEvent<HTMLPreElement>) {
    const element = event.currentTarget
    const nearBottom = distanceFromBottom(element) <= autoScrollBottomThresholdPx
    setStickToBottom(nearBottom)
  }

  function submitConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextRequest = requestFor(mode, instanceInput, source, linesInput)
    if (!nextRequest) {
      setConfigurationError(
        parseLineCount(linesInput) === undefined
          ? `行数必须是 ${1} 到 ${maximumLogLines} 之间的整数。`
          : 'Instance ID 不能为空。',
      )
      return
    }

    setConfigurationError(null)
    setStickToBottom(true)
    setActiveRequest(nextRequest)
  }

  function changeMode(nextMode: LogMode) {
    setMode(nextMode)
    setConfigurationError(null)
  }

  function changeSource(nextSource: string) {
    if ((instanceLogSources as readonly string[]).includes(nextSource)) {
      setSource(nextSource as InstanceLogSource)
      setConfigurationError(null)
    }
  }

  const downloadUrl = getLogDownloadUrl(activeRequest)

  return (
    <div className="page">
      <header className="page-header">
        <p className="eyebrow">OBSERVABILITY</p>
        <h1 className="page-title">日志中心</h1>
        <p className="page-subtitle">查看系统和 Instance 进程日志；过滤只在浏览器中执行，不会改变服务端读取范围。</p>
      </header>

      <form className="filter-bar" aria-label="日志读取设置" noValidate onSubmit={submitConfiguration}>
        <label>
          日志模式
          <select value={mode} onChange={(event) => changeMode(event.target.value === 'INSTANCE' ? 'INSTANCE' : 'SYSTEM')}>
            <option value="SYSTEM">系统日志</option>
            <option value="INSTANCE">Instance 日志</option>
          </select>
        </label>
        {mode === 'INSTANCE' ? (
          <>
            <label>
              Instance ID
              <input
                aria-describedby={configurationError ? 'logs-configuration-error' : undefined}
                value={instanceInput}
                autoComplete="off"
                spellCheck={false}
                onChange={(event) => {
                  setInstanceInput(event.target.value)
                  setConfigurationError(null)
                }}
              />
            </label>
            <label>
              进程来源
              <select value={source} onChange={(event) => changeSource(event.target.value)}>
                {instanceLogSources.map((logSource) => <option key={logSource} value={logSource}>{logSource}</option>)}
              </select>
            </label>
          </>
        ) : null}
        <label>
          行数
          <input
            aria-describedby={configurationError ? 'logs-configuration-error' : undefined}
            inputMode="numeric"
            min={1}
            max={maximumLogLines}
            value={linesInput}
            onChange={(event) => {
              setLinesInput(event.target.value)
              setConfigurationError(null)
            }}
          />
        </label>
        <button type="submit" className="btn btn-primary">加载日志</button>
      </form>
      {configurationError ? <p className="page-error" id="logs-configuration-error" role="alert">{configurationError}</p> : null}

      <section className="logs-toolbar" aria-label="日志操作和过滤">
        <div className="logs-actions">
          <button type="button" className="btn" disabled={logsQuery.isFetching} onClick={() => void logsQuery.refetch()}>手动刷新</button>
          <a className="btn" href={downloadUrl} download>下载当前日志</a>
          <label className="logs-auto-refresh">
            <input type="checkbox" checked={autoRefresh} onChange={(event) => setAutoRefresh(event.target.checked)} />
            每 5 秒自动刷新
          </label>
        </div>
        <div className="logs-client-filters">
          <label>
            级别
            <select value={level} onChange={(event) => setLevel(event.target.value as LogLevel)}>
              {logLevels.map((logLevel) => <option key={logLevel} value={logLevel}>{logLevel}</option>)}
            </select>
          </label>
          <label>
            关键词
            <input value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          </label>
        </div>
      </section>

      {logsQuery.isPending ? <Loading label="正在加载日志…" /> : null}
      {logsQuery.isError ? <ErrorState title="无法加载日志" description={errorMessage(logsQuery.error)} onRetry={() => void logsQuery.refetch()} /> : null}
      {logsQuery.isSuccess ? <LogMetadata log={logsQuery.data} /> : null}
      {logsQuery.isSuccess && !hasLogContent ? <Empty title="暂无日志" description="当前读取范围内没有日志内容。" /> : null}
      {logsQuery.isSuccess && hasLogContent && filteredLines.length === 0 ? <Empty title="没有匹配的日志行" description="请调整级别或关键词过滤条件。" /> : null}
      {logsQuery.isSuccess && filteredLines.length > 0 ? (
        <>
          <pre
            ref={logOutputRef}
            className="logs-output"
            aria-label="日志内容"
            data-stick-to-bottom={stickToBottom ? 'true' : 'false'}
            onScroll={handleLogScroll}
          >
            {logText}
          </pre>
          <p className="logs-scroll-hint" aria-live="polite">
            {stickToBottom
              ? '自动滚动已开启：滚动条保持在底部以跟随新日志。'
              : '自动滚动已暂停：滚动条已离开底部。回到底部附近后会重新跟随。'}
          </p>
        </>
      ) : null}
    </div>
  )
}
