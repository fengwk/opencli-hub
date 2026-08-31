import { describe, expect, it, vi, afterEach } from 'vitest'
import { createInstance, getInstance, listInstances, updateInstance } from '@/features/instances/instances-api'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

afterEach(() => vi.clearAllMocks())

describe('instances-api normalization', () => {
  it('normalizes legacy instance responses lacking maxConcurrency and maxPending to 1 and 5', async () => {
    // Verifies legacy responses missing concurrency and queue fields fallback safely to defaults.
    vi.mocked(apiClient.get).mockResolvedValueOnce([
      { id: '1', code: 'legacy', displayName: 'Legacy' },
    ])

    const [instance] = await listInstances()
    expect(instance.maxConcurrency).toBe(1)
    expect(instance.maxPending).toBe(5)
    expect(instance.proxyMode).toBe('INHERIT')
    expect(instance.proxyServer).toBeNull()
  })

  it('preserves valid maxConcurrency and maxPending including 0 queue bound', async () => {
    // Verifies valid concurrency (1..4) and queue bounds (including 0 for no queue) are preserved.
    vi.mocked(apiClient.get).mockResolvedValueOnce({
      id: '2',
      code: 'custom',
      displayName: 'Custom',
      maxConcurrency: 3,
      maxPending: 0,
      proxyMode: 'DIRECT',
    })

    const instance = await getInstance('2')
    expect(instance.maxConcurrency).toBe(3)
    expect(instance.maxPending).toBe(0)
    expect(instance.proxyMode).toBe('DIRECT')
  })

  it('normalizes invalid out-of-range or non-integer concurrency and pending limits to defaults', async () => {
    // Out-of-bounds or non-integer values returned from backend must fallback safely.
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      id: '3',
      code: 'invalid-bounds',
      displayName: 'Invalid Bounds',
      maxConcurrency: 10, // above max 4
      maxPending: -1,     // below min 0
    })

    const created = await createInstance({
      code: 'invalid-bounds',
      displayName: 'Invalid Bounds',
      websites: ['demo'],
      maxConcurrency: 10,
      maxPending: -1,
      priority: 0,
      proxyMode: 'INHERIT',
      proxyServer: null,
    })
    expect(created.maxConcurrency).toBe(1)
    expect(created.maxPending).toBe(5)
  })

  it('preserves valid payload upon updating an instance', async () => {
    // Updating an instance returns normalized DTO with submitted limits intact.
    vi.mocked(apiClient.put).mockResolvedValueOnce({
      id: '4',
      code: 'updated',
      displayName: 'Updated',
      maxConcurrency: 4,
      maxPending: 50,
      proxyMode: 'CUSTOM',
      proxyServer: 'http://proxy.example.com:8080',
    })

    const updated = await updateInstance('4', {
      code: 'updated',
      displayName: 'Updated',
      websites: ['demo'],
      maxConcurrency: 4,
      maxPending: 50,
      priority: 10,
      proxyMode: 'CUSTOM',
      proxyServer: 'http://proxy.example.com:8080',
    })
    expect(updated.maxConcurrency).toBe(4)
    expect(updated.maxPending).toBe(50)
    expect(updated.proxyMode).toBe('CUSTOM')
    expect(updated.proxyServer).toBe('http://proxy.example.com:8080')
  })
})
