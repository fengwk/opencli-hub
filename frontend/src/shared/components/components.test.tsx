import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  ConfirmDialog,
  Empty,
  ErrorState,
  Loading,
  StatusBadge,
  resolveStatusTone,
} from '@/shared/components'

describe('Loading', () => {
  it('exposes an accessible status role and label', () => {
    render(<Loading label="正在启动" />)
    expect(screen.getByRole('status')).toHaveTextContent('正在启动')
  })
})

describe('Empty', () => {
  it('renders title, description and action', () => {
    render(<Empty title="空" description="没有内容" action={<button>去创建</button>} />)
    expect(screen.getByText('空')).toBeInTheDocument()
    expect(screen.getByText('没有内容')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '去创建' })).toBeInTheDocument()
  })
})

describe('ErrorState', () => {
  it('invokes onRetry when the retry button is clicked', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(<ErrorState description="加载失败" onRetry={onRetry} />)

    expect(screen.getByRole('alert')).toHaveTextContent('加载失败')
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('omits the retry button when no handler is provided', () => {
    render(<ErrorState description="加载失败" />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('StatusBadge', () => {
  it('maps known statuses to their tone', () => {
    expect(resolveStatusTone('RUNNING')).toBe('success')
    expect(resolveStatusTone('starting')).toBe('info')
    expect(resolveStatusTone('ERROR')).toBe('danger')
    // Unknown statuses fall back to neutral so rendering never breaks.
    expect(resolveStatusTone('WHATEVER')).toBe('neutral')
  })

  it('renders the resolved tone and honors an explicit override', () => {
    const { rerender } = render(<StatusBadge status="RUNNING" />)
    expect(screen.getByText('RUNNING')).toHaveAttribute('data-tone', 'success')

    rerender(<StatusBadge status="RUNNING" tone="warning" label="忙碌" />)
    const badge = screen.getByText('忙碌')
    expect(badge).toHaveAttribute('data-tone', 'warning')
  })
})

describe('ConfirmDialog', () => {
  it('is hidden when closed', () => {
    render(
      <ConfirmDialog open={false} title="删除" onConfirm={vi.fn()} onCancel={vi.fn()} />,
    )
    expect(screen.queryByText('删除')).not.toBeInTheDocument()
  })

  it('confirms and cancels through the action buttons', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog
        open
        title="删除实例"
        description="该操作不可撤销"
        confirmLabel="删除"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )

    expect(screen.getByText('删除实例')).toBeInTheDocument()
    expect(screen.getByText('该操作不可撤销')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '删除' }))
    expect(onConfirm).toHaveBeenCalledOnce()

    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('cancels when Escape is pressed and disables actions while busy', async () => {
    const user = userEvent.setup()

    function Harness() {
      const [busy, setBusy] = useState(false)
      const [cancelled, setCancelled] = useState(false)
      return (
        <>
          <button onClick={() => setBusy(true)}>set busy</button>
          <span>{cancelled ? 'cancelled' : 'open'}</span>
          <ConfirmDialog
            open
            title="确认"
            busy={busy}
            onConfirm={vi.fn()}
            onCancel={() => setCancelled(true)}
          />
        </>
      )
    }

    render(<Harness />)

    // Busy state disables the confirm/cancel buttons.
    await user.click(screen.getByRole('button', { name: 'set busy' }))
    expect(screen.getByRole('button', { name: '确认' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled()
    // Escape while busy is ignored.
    await user.keyboard('{Escape}')
    expect(screen.getByText('open')).toBeInTheDocument()
  })

  it('cancels on Escape when not busy', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(<ConfirmDialog open title="确认" onConfirm={vi.fn()} onCancel={onCancel} />)

    await user.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalledOnce()
  })
})
