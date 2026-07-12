import { describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import { ApiError } from '@/shared/api/errors'

const axiosMock = vi.hoisted(() => {
  const client = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    interceptors: {
      response: {
        use: vi.fn(),
      },
    },
  }
  return {
    client,
    create: vi.fn(() => client),
  }
})

vi.mock('axios', () => ({
  default: {
    create: axiosMock.create,
  },
}))

describe('apiClient', () => {
  it('unwraps successful convention4j Result envelopes', () => {
    const [unwrap] = axiosMock.client.interceptors.response.use.mock.calls[0]

    expect(unwrap({ data: { status: 200, code: 'OK', message: '', data: { ok: true } } })).toEqual({
      ok: true,
    })
  })

  it('passes through non-envelope payloads untouched', () => {
    const [unwrap] = axiosMock.client.interceptors.response.use.mock.calls[0]

    // Missing the string `code` -> treated as a raw (non-Result) payload.
    expect(unwrap({ data: { plain: true } })).toEqual({ plain: true })
    expect(unwrap({ data: { status: 200, plain: true } })).toEqual({ status: 200, plain: true })
  })

  it('rejects failed envelopes with an ApiError carrying status/code/errors', async () => {
    const [unwrap] = axiosMock.client.interceptors.response.use.mock.calls[0]

    const rejection = unwrap({
      data: {
        status: 404,
        code: 'INSTANCE_NOT_FOUND',
        message: '实例不存在',
        data: null,
        errors: { instanceId: '9001' },
      },
    })

    await expect(rejection).rejects.toBeInstanceOf(ApiError)
    await rejection.catch((error: ApiError) => {
      expect(error.message).toBe('实例不存在')
      expect(error.status).toBe(404)
      expect(error.code).toBe('INSTANCE_NOT_FOUND')
      expect(error.errors).toEqual({ instanceId: '9001' })
    })
  })

  it('normalizes transport errors, preferring backend envelope over network message', async () => {
    const [, normalizeError] = axiosMock.client.interceptors.response.use.mock.calls[0]

    await normalizeError({
      response: { data: { status: 500, code: 'OPENCLI_EXECUTION_FAILED', message: 'boom' } },
    }).catch((error: ApiError) => {
      expect(error).toBeInstanceOf(ApiError)
      expect(error.code).toBe('OPENCLI_EXECUTION_FAILED')
      expect(error.message).toBe('boom')
    })

    await normalizeError({ message: 'network down' }).catch((error: ApiError) => {
      expect(error.message).toBe('network down')
      expect(error.status).toBe(0)
    })

    await normalizeError({}).catch((error: ApiError) => {
      expect(error.message).toBe('请求失败')
    })
  })

  it('delegates http verbs to the axios instance', async () => {
    axiosMock.client.get.mockResolvedValueOnce({ ok: true })
    axiosMock.client.post.mockResolvedValueOnce({ created: true })
    axiosMock.client.put.mockResolvedValueOnce({ updated: true })
    axiosMock.client.delete.mockResolvedValueOnce(undefined)

    await expect(apiClient.get('/instances', { params: { pageNumber: 1 } })).resolves.toEqual({
      ok: true,
    })
    await expect(apiClient.post('/instances', { code: 'a' })).resolves.toEqual({ created: true })
    await expect(apiClient.put('/instances/1', { name: 'b' })).resolves.toEqual({ updated: true })
    await expect(apiClient.delete('/instances/1')).resolves.toBeUndefined()

    expect(axiosMock.client.get).toHaveBeenCalledWith('/instances', { params: { pageNumber: 1 } })
    expect(axiosMock.client.post).toHaveBeenCalledWith('/instances', { code: 'a' })
    expect(axiosMock.client.put).toHaveBeenCalledWith('/instances/1', { name: 'b' })
    expect(axiosMock.client.delete).toHaveBeenCalledWith('/instances/1')
  })
})
