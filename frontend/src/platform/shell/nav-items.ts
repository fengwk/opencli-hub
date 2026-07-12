import { Boxes, FileClock, ListChecks, ScrollText, TerminalSquare } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
}

/** Primary navigation for the base shell. */
export const navItems: NavItem[] = [
  { to: '/instances', label: 'Instances', icon: Boxes },
  { to: '/executions', label: 'Executions', icon: ListChecks },
  { to: '/commands', label: 'Commands', icon: TerminalSquare },
  { to: '/resources', label: 'Resources', icon: FileClock },
  { to: '/logs', label: 'Logs', icon: ScrollText },
]
