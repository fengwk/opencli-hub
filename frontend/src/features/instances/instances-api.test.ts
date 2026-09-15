import { describe, expect, it, vi, afterEach } from 'vitest'
import { createInstance, getInstance, listInstances, updateInstance } from '@/features/instances/instances-api'
import { apiClient } from '@/shared/api/client'

vi.mock('@/shared/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

afterEach(() => vi.clearAllMocks())

describe('instances-api normalization', () => {
  it('normalizes legacy instance responses lacking maxConcurrency, maxPending, and warmTabTtlSeconds to defaults', async () => {
    // Verifies legacy responses missing concurrency, queue fields, and warm-tab TTL fallback safely to defaults.
    vi.mocked(apiClient.get).mockResolvedValueOnce([
      { id: '1', code: 'legacy', displayName: 'Legacy' },
    ])

    const [instance] = await listInstances()
    expect(instance.maxConcurrency).toBe(1)
    expect(instance.maxPending).toBe(5)
    expect(instance.warmTabTtlSeconds).toBe(1800)
    expect(instance.proxyMode).toBe('INHERIT')
    expect(instance.proxyServer).toBeNull()
  })

  it('preserves valid maxConcurrency, maxPending, and warmTabTtlSeconds including -1 and 0', async () => {
    // Verifies valid concurrency (1..4), queue bounds (including 0), and TTL (-1, 0, positive) are preserved.
    vi.mocked(apiClient.get).mockResolvedValueOnce({
      id: '2',
      code: 'custom',
      displayName: 'Custom',
      maxConcurrency: 3,
      maxPending: 0,
      warmTabTtlSeconds: -1,
      proxyMode: 'DIRECT',
    })

    const instance = await getInstance('2')
    expect(instance.maxConcurrency).toBe(3)
    expect(instance.maxPending).toBe(0)
    expect(instance.warmTabTtlSeconds).toBe(-1)
    expect(instance.proxyMode).toBe('DIRECT')
  })

  it('normalizes invalid out-of-range or non-integer concurrency, pending, and TTL to defaults', async () => {
    // Out-of-bounds or non-integer values returned from backend must fallback safely.
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      id: '3',
      code: 'invalid-bounds',
      displayName: 'Invalid Bounds',
      maxConcurrency: 10, // above max 4
      maxPending: -1,     // below min 0
      warmTabTtlSeconds: -5, // below min -1
    })

    const created = await createInstance({
      code: 'invalid-bounds',
      displayName: 'Invalid Bounds',
      websites: ['demo'],
      maxConcurrency: 10,
      maxPending: -1,
      priority: 0,
      warmTabTtlSeconds: -5,
      proxyMode: 'INHERIT',
      proxyServer: null,
    })
    expect(created.maxConcurrency).toBe(1)
    expect(created.maxPending).toBe(5)
    expect(created.warmTabTtlSeconds).toBe(1800)
  })

  it('preserves valid payload upon updating an instance', async () => {
    // Updating an instance returns normalized DTO with submitted limits and TTL intact.
    vi.mocked(apiClient.put).mockResolvedValueOnce({
      id: '4',
      code: 'updated',
      displayName: 'Updated',
      maxConcurrency: 4,
      maxPending: 50,
      warmTabTtlSeconds: 0,
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
      warmTabTtlSeconds: 0,
      proxyMode: 'CUSTOM',
      proxyServer: 'http://proxy.example.com:8080',
    })
    expect(updated.maxConcurrency).toBe(4)
    expect(updated.maxPending).toBe(50)
    expect(updated.warmTabTtlSeconds).toBe(0)
    expect(updated.proxyMode).toBe('CUSTOM')
    expect(updated.proxyServer).toBe('http://proxy.example.com:8080')
  })
})
