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
  it('populates defaults for maxConcurrency (1), maxPending (5), and warmTabTtlSeconds (1800) in creation mode', async () => {
    // Verifies creation defaults: maxConcurrency defaults to 1, maxPending defaults to 5, and warmTabTtlSeconds defaults to 1800.
    renderForm()
    expect(screen.getByRole('spinbutton', { name: '最大并发数' })).toHaveValue(1)
    expect(screen.getByRole('spinbutton', { name: '最大待处理数' })).toHaveValue(5)
    expect(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' })).toHaveValue(1800)
  })

  it('populates provided initialValues including maxPending=0 and warmTabTtlSeconds correctly in edit mode', async () => {
    // Verifies initial values with maxPending=0 and custom TTL are preserved and displayed correctly.
    renderForm({
      initialValues: {
        code: 'test-inst',
        displayName: 'Test Instance',
        websites: ['demo'],
        maxConcurrency: 3,
        maxPending: 0,
        priority: 10,
        warmTabTtlSeconds: -1,
        proxyMode: 'INHERIT',
        proxyServer: null,
      },
    })
    expect(screen.getByRole('textbox', { name: '实例代码' })).toHaveValue('test-inst')
    expect(screen.getByRole('textbox', { name: '显示名称' })).toHaveValue('Test Instance')
    expect(screen.getByRole('spinbutton', { name: '最大并发数' })).toHaveValue(3)
    expect(screen.getByRole('spinbutton', { name: '最大待处理数' })).toHaveValue(0)
    expect(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' })).toHaveValue(-1)
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
      warmTabTtlSeconds: 1800,
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

  it('rejects an empty maxPending instead of treating it as zero', async () => {
    // Empty input must not silently opt the instance into no-queue mode.
    const user = userEvent.setup()
    const { onSubmit } = renderForm()
    await screen.findByRole('checkbox', { name: 'demo' })

    await user.type(screen.getByRole('textbox', { name: '实例代码' }), 'inst-empty')
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'Empty Pending')
    await user.click(screen.getByRole('checkbox', { name: 'demo' }))
    await user.clear(screen.getByRole('spinbutton', { name: '最大待处理数' }))
    await user.click(screen.getByRole('button', { name: '保存' }))

    expect(screen.getByRole('alert')).toHaveTextContent('最大待处理数必须是 0 到 50 之间的整数。')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('validates bounds for warmTabTtlSeconds (-1..2147483647) and allows -1 and 0', async () => {
    // Verifies TTL bounds validation and ensures -1 and 0 are accepted and submitted.
    const user = userEvent.setup()
    const { onSubmit } = renderForm()
    await screen.findByRole('checkbox', { name: 'demo' })

    await user.type(screen.getByRole('textbox', { name: '实例代码' }), 'inst-ttl')
    await user.type(screen.getByRole('textbox', { name: '显示名称' }), 'TTL Instance')
    await user.click(screen.getByRole('checkbox', { name: 'demo' }))

    // Test warmTabTtlSeconds < -1 (e.g. -2)
    await user.clear(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' }))
    await user.type(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' }), '-2')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(screen.getByRole('alert')).toHaveTextContent('闲置标签页保留时间必须是 -1 到 2147483647 之间的整数秒')

    // Boundary values -1 and 0 are both accepted.
    await user.clear(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' }))
    await user.type(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' }), '-1')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      warmTabTtlSeconds: -1,
    }))

    await user.clear(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' }))
    await user.type(screen.getByRole('spinbutton', { name: '闲置标签页保留时间（秒）' }), '0')
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({
      warmTabTtlSeconds: 0,
    }))
  })
})
