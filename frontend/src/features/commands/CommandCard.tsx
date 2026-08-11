import { ChevronDown, ChevronUp, Copy, SlidersHorizontal } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { buildCommandCurlTemplate } from '@/features/commands/curl-template'
import { StatusBadge } from '@/shared/components'
import type { HubCommand, OutputRuleUpdate, OutputTargetType } from '@/features/commands/types'

interface CommandCardProps {
  command: HubCommand
  busy: boolean
  onBlacklist: (command: HubCommand, reason: string) => Promise<boolean>
  onUnblacklist: (command: HubCommand) => Promise<boolean>
  onSaveOutputRule: (command: HubCommand, rule: OutputRuleUpdate) => Promise<boolean>
  onDeleteOutputRule: (command: HubCommand) => Promise<boolean>
}

function outputArguments(command: HubCommand) {
  // Positional arguments are not output-rule candidates: the backend rejects them
  // for managed output rules (only named value arguments are eligible).
  return (command.args ?? []).filter((argument) => !argument.positional && (argument.valueRequired || argument.required))
}

function isSafeFileName(fileName: string): boolean {
  return /^[A-Za-z0-9._-]+$/.test(fileName) && fileName !== '.' && fileName !== '..'
}

/** Compact by default so a large catalog does not mount every policy editor. */
export function CommandCard({
  command,
  busy,
  onBlacklist,
  onUnblacklist,
  onSaveOutputRule,
  onDeleteOutputRule,
}: CommandCardProps) {
  const argumentsForOutput = outputArguments(command)
  // Editor options are the visible eligible args plus the persisted rule's argument
  // name: the managed output argument is hidden from the public args contract, so it
  // can only appear here as the current rule's value and must stay editable as-is.
  const outputArgumentNames = [
    ...new Set([
      ...argumentsForOutput.map((argument) => argument.name),
      ...(command.outputRule?.argumentName ? [command.outputRule.argumentName] : []),
    ]),
  ]
  const [expanded, setExpanded] = useState(false)
  const [blacklistReason, setBlacklistReason] = useState(command.blacklistReason ?? '')
  const [editingOutputRule, setEditingOutputRule] = useState(false)
  const [argumentName, setArgumentName] = useState(command.outputRule?.argumentName ?? argumentsForOutput[0]?.name ?? '')
  const [targetType, setTargetType] = useState<OutputTargetType>(command.outputRule?.targetType ?? 'DIRECTORY')
  const [fileName, setFileName] = useState(command.outputRule?.fileName ?? '')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [curlCopied, setCurlCopied] = useState(false)
  const [curlCopyError, setCurlCopyError] = useState<string | null>(null)
  const detailsId = `command-details-${command.commandKey}`
  const curlTemplate = buildCommandCurlTemplate(command)

  useEffect(() => {
    setBlacklistReason(command.blacklistReason ?? '')
    setArgumentName(command.outputRule?.argumentName ?? outputArguments(command)[0]?.name ?? '')
    setTargetType(command.outputRule?.targetType ?? 'DIRECTORY')
    setFileName(command.outputRule?.fileName ?? '')
    setValidationError(null)
    setCurlCopied(false)
    setCurlCopyError(null)
  }, [command])

  async function submitBlacklist(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    await onBlacklist(command, blacklistReason.trim())
  }

  async function submitOutputRule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedFileName = fileName.trim()
    if (!argumentName) {
      setValidationError('请选择一个接受值的输出参数。')
      return
    }
    if (targetType === 'FILE' && !normalizedFileName) {
      setValidationError('FILE 输出目标必须填写文件名。')
      return
    }
    if (targetType === 'DIRECTORY' && normalizedFileName) {
      setValidationError('DIRECTORY 输出目标不能填写文件名。')
      return
    }
    if (targetType === 'FILE' && !isSafeFileName(normalizedFileName)) {
      setValidationError('文件名只能包含字母、数字、点、下划线和连字符。')
      return
    }

    setValidationError(null)
    const saved = await onSaveOutputRule(command, {
      argumentName,
      targetType,
      fileName: targetType === 'FILE' ? normalizedFileName : null,
    })
    if (saved) setEditingOutputRule(false)
  }

  async function copyCurlTemplate() {
    setCurlCopied(false)
    setCurlCopyError(null)
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('浏览器不支持剪贴板写入，请使用 HTTPS 或 localhost。')
      }
      await navigator.clipboard.writeText(curlTemplate)
      setCurlCopied(true)
    } catch (error) {
      setCurlCopyError(error instanceof Error ? error.message : '无法复制 curl 模板。')
    }
  }

  return (
    <article className={`command-card ${expanded ? 'is-expanded' : ''}`}>
      <div className="command-card-header">
        <div>
          <p className="eyebrow">{command.site} · {command.name}</p>
          <h2 className="card-title">{command.commandKey}</h2>
          {command.description ? <p className="card-description">{command.description}</p> : null}
        </div>
        <div className="badge-row">
          <StatusBadge status={command.access} tone={command.access === 'WRITE' ? 'warning' : 'info'} />
          <StatusBadge status={command.siteSession ?? '未知 session'} />
          <StatusBadge status={command.blacklisted ? '已禁用' : '可用'} tone={command.blacklisted ? 'danger' : 'success'} />
        </div>
      </div>
      <div className="command-card-summary">
        <span>{command.browser ? '浏览器能力' : '本地能力'}</span>
        <span>{command.args?.length ?? 0} 个参数</span>
        <span>{command.outputRule ? `输出：${command.outputRule.targetType}` : '未配置输出规则'}</span>
        {command.aliases?.length ? <span>别名：{command.aliases.join(', ')}</span> : null}
      </div>
      <button
        type="button"
        className="btn command-expand"
        aria-expanded={expanded}
        aria-controls={detailsId}
        onClick={() => setExpanded((value) => !value)}
      >
        <SlidersHorizontal aria-hidden="true" />{expanded ? '收起详情与策略' : '查看详情与策略'}
        {expanded ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
      </button>

      {expanded ? <div id={detailsId} className="command-details">
        <dl className="metadata-grid">
          <div><dt>站点</dt><dd>{command.site}</dd></div>
          <div><dt>命令</dt><dd>{command.name}</dd></div>
          <div><dt>默认窗口</dt><dd>{command.defaultWindowMode ?? '—'}</dd></div>
          <div><dt>会话模式</dt><dd>{command.siteSession ?? '—'}</dd></div>
        </dl>

        <section aria-label={`${command.commandKey} 参数`} className="command-section">
          <h3>参数定义</h3>
          {command.args?.length ? (
            <ul className="argument-list">
              {command.args.map((argument) => (
                <li key={argument.name}>
                  <strong>{argument.positional ? argument.name : `--${argument.name}`}</strong>
                  <span>{argument.type}{argument.required ? '，必填' : ''}{argument.valueRequired ? '，需要值' : ''}</span>
                  {argument.choices?.length ? <span>可选值：{argument.choices.join(', ')}</span> : null}
                  {argument.help ? <span>{argument.help}</span> : null}
                </li>
              ))}
            </ul>
          ) : <p className="muted">该命令没有业务参数。</p>}
        </section>

        <section aria-label={`${command.commandKey} curl 模板`} className="command-section command-curl-section">
          <div className="section-heading-row">
            <h3>受控执行 curl</h3>
            <button type="button" className="btn command-copy" onClick={() => void copyCurlTemplate()}>
              <Copy aria-hidden="true" />{curlCopied ? '已复制' : '复制 curl 模板'}
            </button>
          </div>
          <p className="muted">模板仅包含该命令的必填参数；替换尖括号占位符，并按 Gateway 配置补充认证。</p>
          <pre className="command-curl-template"><code>{curlTemplate}</code></pre>
          {curlCopyError ? <p className="inline-error" role="alert">{curlCopyError}</p> : null}
          {curlCopied ? <p className="command-copy-notice" role="status">curl 模板已复制到本机剪贴板。</p> : null}
        </section>

        <section aria-label={`${command.commandKey} 黑名单`} className="command-section policy-section">
          <h3>黑名单策略</h3>
          {command.blacklisted ? (
            <div className="policy-row">
              <span>{command.blacklistReason ? `原因：${command.blacklistReason}` : '未填写原因'}</span>
              <button type="button" className="btn" disabled={busy} onClick={() => void onUnblacklist(command)}>解除黑名单</button>
            </div>
          ) : (
            <form className="inline-form" onSubmit={(event) => void submitBlacklist(event)}>
              <label>
                黑名单原因（可选）
                <input value={blacklistReason} maxLength={512} onChange={(event) => setBlacklistReason(event.target.value)} />
              </label>
              <button type="submit" className="btn btn-danger btn-quiet-danger" disabled={busy}>加入黑名单</button>
            </form>
          )}
        </section>

        <section aria-label={`${command.commandKey} 输出规则`} className="command-section policy-section">
          <div className="section-heading-row">
            <h3>输出资源规则</h3>
            {command.outputRule ? <button type="button" className="btn" disabled={busy} onClick={() => void onDeleteOutputRule(command)}>删除输出规则</button> : null}
          </div>
          {command.outputRule ? <p>参数 <strong>{command.outputRule.argumentName}</strong> → {command.outputRule.targetType}{command.outputRule.fileName ? `（${command.outputRule.fileName}）` : ''}</p> : <p className="muted">尚未配置输出资源规则。</p>}
          {editingOutputRule ? (
            <form className="inline-form output-rule-form" onSubmit={(event) => void submitOutputRule(event)}>
              <label>
                输出参数
                <select value={argumentName} onChange={(event) => setArgumentName(event.target.value)}>
                  <option value="">请选择参数</option>
                  {outputArgumentNames.map((name) => <option key={name} value={name}>{name}</option>)}
                </select>
              </label>
              <label>
                输出目标
                <select value={targetType} onChange={(event) => {
                  const nextTargetType = event.target.value as OutputTargetType
                  setTargetType(nextTargetType)
                  if (nextTargetType === 'DIRECTORY') setFileName('')
                }}>
                  <option value="DIRECTORY">DIRECTORY</option>
                  <option value="FILE">FILE</option>
                </select>
              </label>
              <label>
                文件名{targetType === 'FILE' ? '（必填）' : '（DIRECTORY 不填写）'}
                <input value={fileName} disabled={targetType === 'DIRECTORY'} maxLength={255} onChange={(event) => setFileName(event.target.value)} />
              </label>
              {validationError ? <p className="inline-error" role="alert">{validationError}</p> : null}
              <div className="form-actions">
                <button type="button" className="btn" disabled={busy} onClick={() => setEditingOutputRule(false)}>取消</button>
                <button type="submit" className="btn btn-primary" disabled={busy || outputArgumentNames.length === 0}>保存输出规则</button>
              </div>
            </form>
          ) : <button type="button" className="btn btn-primary" disabled={busy || outputArgumentNames.length === 0} onClick={() => setEditingOutputRule(true)}>编辑输出规则</button>}
          {!outputArgumentNames.length ? <p className="muted">该命令没有可用于输出规则的参数。</p> : null}
        </section>
      </div> : null}
    </article>
  )
}
