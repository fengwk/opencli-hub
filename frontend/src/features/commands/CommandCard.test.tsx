import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CommandCard } from '@/features/commands/CommandCard'
import type { HubCommand } from '@/features/commands/types'

/**
 * A command whose persisted output rule targets `op` — a platform-managed argument
 * that Hub hides from the public args contract, so it never appears in command.args.
 */
const managedRuleCommand: HubCommand = {
  commandKey: 'demo/search',
  site: 'demo',
  name: 'search',
  aliases: null,
  description: 'Search the demo site',
  access: 'READ',
  browser: true,
  args: [{
    name: 'prompt',
    type: 'string',
    required: true,
    valueRequired: true,
    positional: false,
    choices: null,
    defaultValue: null,
    help: 'Image prompt',
  }],
  siteSession: 'EPHEMERAL',
  defaultWindowMode: 'new-tab',
  blacklisted: false,
  blacklistReason: null,
  outputRule: {
    id: 'rule-1',
    commandKey: 'demo/search',
    argumentName: 'op',
    targetType: 'DIRECTORY',
    fileName: null,
    createTime: '2026-07-13T10:00:00',
    updateTime: '2026-07-13T10:00:00',
  },
}

function renderCard(command: HubCommand = managedRuleCommand) {
  const onBlacklist = vi.fn().mockResolvedValue(true)
  const onUnblacklist = vi.fn().mockResolvedValue(true)
  const onSaveOutputRule = vi.fn().mockResolvedValue(true)
  const onDeleteOutputRule = vi.fn().mockResolvedValue(true)
  render(
    <CommandCard
      command={command}
      busy={false}
      onBlacklist={onBlacklist}
      onUnblacklist={onUnblacklist}
      onSaveOutputRule={onSaveOutputRule}
      onDeleteOutputRule={onDeleteOutputRule}
    />,
  )
  return { onBlacklist, onUnblacklist, onSaveOutputRule, onDeleteOutputRule }
}

describe('CommandCard', () => {
  it('shows an existing rule whose argument is hidden from args and edits it as-is', async () => {
    const user = userEvent.setup()
    const { onSaveOutputRule } = renderCard()

    // The collapsed summary exposes the rule type.
    expect(screen.getByText('输出：DIRECTORY')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))

    // The rule metadata and its delete control are visible, without a false
    // incompatibility warning just because `op` is not in the public args.
    expect(screen.getByText('参数 → DIRECTORY')).toBeInTheDocument()
    expect(screen.queryByText('输出规则与当前命令目录不兼容，请更新或删除该规则。')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '删除输出规则' })).toBeEnabled()

    // Editing is allowed: the select still contains and selects the managed
    // argument name so the existing rule can be saved back unchanged.
    await user.click(screen.getByRole('button', { name: '编辑输出规则' }))
    const argumentSelect = screen.getByRole('combobox', { name: '输出参数' })
    expect(argumentSelect).toHaveValue('op')
    expect(screen.getByRole('option', { name: 'op' })).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: '输出目标' }), 'FILE')
    await user.type(screen.getByRole('textbox', { name: /文件名/ }), 'result.json')
    await user.click(screen.getByRole('button', { name: '保存输出规则' }))

    expect(onSaveOutputRule).toHaveBeenCalledWith(managedRuleCommand, {
      argumentName: 'op',
      targetType: 'FILE',
      fileName: 'result.json',
    })
  })

  it('deletes an existing rule through the card control', async () => {
    const user = userEvent.setup()
    const { onDeleteOutputRule } = renderCard()

    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))
    await user.click(screen.getByRole('button', { name: '删除输出规则' }))

    expect(onDeleteOutputRule).toHaveBeenCalledWith(managedRuleCommand)
  })

  it('keeps FILE safety validation when editing an existing rule', async () => {
    const user = userEvent.setup()
    const { onSaveOutputRule } = renderCard()

    await user.click(screen.getByRole('button', { name: '查看详情与策略' }))
    await user.click(screen.getByRole('button', { name: '编辑输出规则' }))
    await user.selectOptions(screen.getByRole('combobox', { name: '输出目标' }), 'FILE')
    await user.type(screen.getByRole('textbox', { name: /文件名/ }), 'nested/result.json')
    await user.click(screen.getByRole('button', { name: '保存输出规则' }))

    expect(screen.getByRole('alert')).toHaveTextContent('文件名只能包含')
    expect(onSaveOutputRule).not.toHaveBeenCalled()
  })
})
