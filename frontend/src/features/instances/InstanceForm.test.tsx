import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { InstanceForm } from '@/features/instances/InstanceForm'

vi.mock('@/features/commands/commands-api', () => ({
  listCommands: vi.fn(() => Promise.resolve([{ site: 'demo', name: 'search' }])),
}))

afterEach(() => vi.clearAllMocks())

function renderForm(props: Partial<React.ComponentProps<typeof InstanceForm>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onSubmit = props.onSubmit ?? vi.fn()
  const result = render(
    <QueryClientProvider client={queryClient}>
      <InstanceForm
        submitLabel="保存"
        onSubmit={onSubmit}
        {...props}
      />
    </QueryClientProvider>,
  )
  return { ...result, onSubmit }
}

describe('InstanceForm', () => {
  it('populates defaults for maxConcurrency (1) and maxPending (5) in creation mode', async () => {
    // Verifies creation defaults: maxConcurrency defaults to 1 and maxPending defaults to 5.
    renderForm()
    expect(screen.getByRole('spinbutton', { name: '最大并发数' })).toHaveValue(1)
    expect(screen.getByRole('spinbutton', { name: '最大待处理数' })).toHaveValue(5)
  })

  it('populates provided initialValues including maxPending=0 correctly in edit mode', async () => {
    // Verifies initial values with maxPending=0 (no queue) are preserved and displayed as 0.
    renderForm({
      initialValues: {
        code: 'test-inst',
        displayName: 'Test Instance',
        websites: ['demo'],
        maxConcurrency: 3,
        maxPending: 0,
        priority: 10,
        proxyMode: 'INHERIT',
        proxyServer: null,
      },
    })
    expect(screen.getByRole('textbox', { name: '实例代码' })).toHaveValue('test-inst')
    expect(screen.getByRole('textbox', { name: '显示名称' })).toHaveValue('Test Instance')
    expect(screen.getByRole('spinbutton', { name: '最大并发数' })).toHaveValue(3)
    expect(screen.getByRole('spinbutton', { name: '最大待处理数' })).toHaveValue(0)
  })

  it('submits maxPending=0 and custom maxConcurrency successfully', async () => {
    // Verifies maxPending=0 is submitted without error when operator disables queuing.
    const user = userEvent.setup()
    const { onSubmit } = renderForm()
    await screen.findByRole('checkbox', { name: 'demo' })

    await user.type(screen.getByRole('textbox', { name: '实例代码' }), 'inst-zero')
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'Instance Zero')
    await user.clear(screen.getByRole('spinbutton', { name: '最大并发数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大并发数' }), '4')
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大待处理数' }), '0')
    await user.click(screen.getByRole('checkbox', { name: 'demo' }))
    await user.click(screen.getByRole('button', { name: '保存' }))

    expect(onSubmit).toHaveBeenCalledWith({
      code: 'inst-zero',
      displayName: 'Instance Zero',
      websites: ['demo'],
      maxConcurrency: 4,
      maxPending: 0,
      priority: 0,
      proxyMode: 'INHERIT',
      proxyServer: null,
    })
  })

  it('validates bounds for maxConcurrency (1..4) and maxPending (0..50)', async () => {
    // Verifies boundary validation on both concurrency and pending queue inputs.
    const user = userEvent.setup()
    const { onSubmit } = renderForm()
    await screen.findByRole('checkbox', { name: 'demo' })

    await user.type(screen.getByRole('textbox', { name: '实例代码' }), 'inst-bounds')
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'Bounds Instance')
    await user.click(screen.getByRole('checkbox', { name: 'demo' }))

    // Test maxConcurrency lower bound (< 1)
    await user.clear(screen.getByRole('spinbutton', { name: '最大并发数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大并发数' }), '0')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByRole('alert')).toHaveTextContent('最大并发数必须是 1 到 4 之间的整数。')

    // Test maxConcurrency upper bound (> 4)
    await user.clear(screen.getByRole('spinbutton', { name: '最大并发数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大并发数' }), '5')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByRole('alert')).toHaveTextContent('最大并发数必须是 1 到 4 之间的整数。')

    // Reset maxConcurrency to valid
    await user.clear(screen.getByRole('spinbutton', { name: '最大并发数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大并发数' }), '2')

    // Test maxPending lower bound (< 0)
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大待处理数' }), '-1')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByRole('alert')).toHaveTextContent('最大待处理数必须是 0 到 50 之间的整数。')

    // Test maxPending upper bound (> 50)
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.type(screen.getByRole('spinbutton', { name: '最大待处理数' }), '51')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByRole('alert')).toHaveTextContent('最大待处理数必须是 0 到 50 之间的整数。')

    expect(onSubmit).not.toHaveBeenCalled()
  })
})
