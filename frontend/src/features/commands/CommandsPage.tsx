import { Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CommandCard } from '@/features/commands/CommandCard'
import {
  blacklistCommand,
  deleteOutputRule,
  listCommands,
  saveOutputRule,
  unblacklistCommand,
} from '@/features/commands/commands-api'
import type { HubCommand, OutputRuleUpdate } from '@/features/commands/types'
import { Empty, ErrorState, Loading } from '@/shared/components'

const allFilterValue = 'ALL'
type BlacklistFilter = 'ALL' | 'BLACKLISTED' | 'AVAILABLE'

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

function CatalogMetrics({ commands }: { commands: HubCommand[] }) {
  const metrics = [
    ['目录命令', commands.length],
    ['支持站点', new Set(commands.map((command) => command.site)).size],
    ['已禁用', commands.filter((command) => command.blacklisted).length],
    ['输出规则', commands.filter((command) => command.outputRule).length],
  ]
  return <section className="catalog-metrics" aria-label="命令目录概览">
    {metrics.map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}
  </section>
}

export function CommandsPage() {
  const queryClient = useQueryClient()
  const [website, setWebsite] = useState(allFilterValue)
  const [access, setAccess] = useState(allFilterValue)
  const [session, setSession] = useState(allFilterValue)
  const [blacklist, setBlacklist] = useState<BlacklistFilter>('ALL')
  const [keyword, setKeyword] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionPending, setActionPending] = useState(false)

  const commandsQuery = useQuery({
    queryKey: ['commands'],
    queryFn: () => listCommands(),
  })

  const websites = useMemo(() => [...new Set((commandsQuery.data ?? []).map((command) => command.site))].sort(), [commandsQuery.data])
  const visibleCommands = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLocaleLowerCase()
    return (commandsQuery.data ?? []).filter((command) => {
      const keywordMatches = !normalizedKeyword || [command.commandKey, command.name, command.site, command.description, ...(command.aliases ?? [])]
        .some((value) => value?.toLocaleLowerCase().includes(normalizedKeyword))
      const websiteMatches = website === allFilterValue || command.site === website
      const accessMatches = access === allFilterValue || command.access === access
      const sessionMatches = session === allFilterValue || command.siteSession === session
      const blacklistMatches = blacklist === 'ALL' || (blacklist === 'BLACKLISTED' ? command.blacklisted : !command.blacklisted)
      return keywordMatches && websiteMatches && accessMatches && sessionMatches && blacklistMatches
    })
  }, [access, blacklist, commandsQuery.data, keyword, session, website])

  async function runCommandAction(action: () => Promise<HubCommand>): Promise<boolean> {
    setActionError(null)
    setActionPending(true)
    try {
      await action()
      await queryClient.invalidateQueries({ queryKey: ['commands'] })
      return true
    } catch (error) {
      setActionError(errorMessage(error))
      return false
    } finally {
      setActionPending(false)
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <p className="eyebrow"><span className="status-dot" />CAPABILITY CATALOG</p>
        <h1 className="page-title">命令目录</h1>
        <p className="page-subtitle">浏览站点能力、管理黑名单和输出资源策略；这里不执行命令。</p>
      </header>
      <aside className="catalog-guidance" role="note">
        <strong>执行入口保持不变</strong>
        <span>客户端仍通过 <code>POST /api/opencli/execute</code> 发起执行，结果和资源将在 <b>执行记录</b> 中查看。</span>
      </aside>

      {commandsQuery.isSuccess ? <CatalogMetrics commands={commandsQuery.data} /> : null}
      <section className="filter-bar command-filters" aria-label="命令筛选">
        <label className="command-search">
          搜索命令
          <span><Search aria-hidden="true" /><input value={keyword} placeholder="名称、站点或别名" onChange={(event) => setKeyword(event.target.value)} /></span>
        </label>
        <label>
          网站
          <select value={website} onChange={(event) => setWebsite(event.target.value)}>
            <option value={allFilterValue}>全部网站</option>
            {websites.map((site) => <option key={site} value={site}>{site}</option>)}
          </select>
        </label>
        <label>
          访问权限
          <select value={access} onChange={(event) => setAccess(event.target.value)}>
            <option value={allFilterValue}>全部</option>
            <option value="READ">READ</option>
            <option value="WRITE">WRITE</option>
          </select>
        </label>
        <label>
          Session
          <select value={session} onChange={(event) => setSession(event.target.value)}>
            <option value={allFilterValue}>全部</option>
            <option value="EPHEMERAL">EPHEMERAL</option>
            <option value="PERSISTENT">PERSISTENT</option>
          </select>
        </label>
        <label>
          黑名单状态
          <select value={blacklist} onChange={(event) => setBlacklist(event.target.value as BlacklistFilter)}>
            <option value="ALL">全部</option>
            <option value="AVAILABLE">可用</option>
            <option value="BLACKLISTED">已禁用</option>
          </select>
        </label>
      </section>
      {commandsQuery.isSuccess ? <p className="result-count" role="status">显示 {visibleCommands.length} / {commandsQuery.data.length} 个命令</p> : null}

      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}
      {commandsQuery.isPending ? <Loading label="正在加载命令目录…" /> : null}
      {commandsQuery.isError ? <ErrorState title="无法加载命令目录" description={errorMessage(commandsQuery.error)} onRetry={() => void commandsQuery.refetch()} /> : null}
      {commandsQuery.isSuccess && visibleCommands.length === 0 ? <Empty title="没有匹配的命令" description="请调整搜索关键词或筛选条件后重试。" /> : null}
      {commandsQuery.isSuccess && visibleCommands.length > 0 ? (
        <div className="command-list">
          {visibleCommands.map((command) => (
            <CommandCard
              key={command.commandKey}
              command={command}
              busy={actionPending}
              onBlacklist={(target, reason) => runCommandAction(() => blacklistCommand(target, reason))}
              onUnblacklist={(target) => runCommandAction(() => unblacklistCommand(target))}
              onSaveOutputRule={(target, rule: OutputRuleUpdate) => runCommandAction(() => saveOutputRule(target, rule))}
              onDeleteOutputRule={(target) => runCommandAction(() => deleteOutputRule(target))}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}
