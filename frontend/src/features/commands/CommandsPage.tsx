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

export function CommandsPage() {
  const queryClient = useQueryClient()
  const [website, setWebsite] = useState(allFilterValue)
  const [access, setAccess] = useState(allFilterValue)
  const [session, setSession] = useState(allFilterValue)
  const [blacklist, setBlacklist] = useState<BlacklistFilter>('ALL')
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionPending, setActionPending] = useState(false)

  const commandsQuery = useQuery({
    queryKey: ['commands', website],
    queryFn: () => listCommands(website === allFilterValue ? undefined : website),
  })

  const websites = useMemo(
    () => [...new Set((commandsQuery.data ?? []).map((command) => command.site))].sort(),
    [commandsQuery.data],
  )
  const visibleCommands = useMemo(() => (commandsQuery.data ?? []).filter((command) => {
    const accessMatches = access === allFilterValue || command.access === access
    const sessionMatches = session === allFilterValue || command.siteSession === session
    const blacklistMatches = blacklist === 'ALL'
      || (blacklist === 'BLACKLISTED' ? command.blacklisted : !command.blacklisted)
    return accessMatches && sessionMatches && blacklistMatches
  }), [access, blacklist, commandsQuery.data, session])

  async function runCommandAction(action: () => Promise<HubCommand>) {
    setActionError(null)
    setActionPending(true)
    try {
      await action()
      await queryClient.invalidateQueries({ queryKey: ['commands'] })
    } catch (error) {
      setActionError(errorMessage(error))
    } finally {
      setActionPending(false)
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1 className="page-title">Commands</h1>
        <p className="page-subtitle">查看公开浏览器命令，并管理黑名单和输出资源规则。</p>
      </header>

      <section className="filter-bar" aria-label="命令筛选">
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

      {actionError ? <p className="page-error" role="alert">{actionError}</p> : null}
      {commandsQuery.isPending ? <Loading label="正在加载命令目录…" /> : null}
      {commandsQuery.isError ? <ErrorState title="无法加载命令目录" description={errorMessage(commandsQuery.error)} onRetry={() => void commandsQuery.refetch()} /> : null}
      {commandsQuery.isSuccess && visibleCommands.length === 0 ? <Empty title="没有匹配的命令" description="请调整筛选条件后重试。" /> : null}
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
