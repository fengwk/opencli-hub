import { Boxes, FileClock, ListChecks, ScrollText, Settings, TerminalSquare } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  technicalLabel: string
  icon: LucideIcon
}

/** Primary product navigation shown in the responsive top bar. */
export const navItems: NavItem[] = [
  { to: '/instances', label: '实例管理', technicalLabel: 'INSTANCES', icon: Boxes },
  { to: '/executions', label: '执行记录', technicalLabel: 'EXECUTIONS', icon: ListChecks },
  { to: '/commands', label: '命令目录', technicalLabel: 'COMMANDS', icon: TerminalSquare },
  { to: '/resources', label: '资源中心', technicalLabel: 'RESOURCES', icon: FileClock },
  { to: '/settings', label: '系统设置', technicalLabel: 'SETTINGS', icon: Settings },
  { to: '/logs', label: '日志中心', technicalLabel: 'LOGS', icon: ScrollText },
]
