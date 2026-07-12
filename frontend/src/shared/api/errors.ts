import type { ResultEnvelope } from '@/shared/api/contracts'

/**
 * Unified API error raised for every failed request, whether the failure is a
 * non-2xx convention4j `Result` envelope or a transport/network problem. UI
 * code can rely on `message` for display and on `code`/`status`/`errors` for
 * branching without inspecting Axios internals.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly errors: Record<string, unknown> | null

  constructor(message: string, options: {
    status?: number
    code?: string | null
    errors?: Record<string, unknown> | null
    cause?: unknown
  } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = options.status ?? 0
    this.code = options.code ?? null
    this.errors = options.errors ?? null
    if (options.cause !== undefined) {
      this.cause = options.cause
    }
  }

  /** Build an ApiError from a failed convention4j Result envelope. */
  static fromEnvelope(envelope: ResultEnvelope<unknown>): ApiError {
    return new ApiError(envelope.message || '请求失败', {
      status: envelope.status,
      code: envelope.code ?? null,
      errors: envelope.errors ?? null,
    })
  }

  /** Build an ApiError from an Axios/transport-level failure. */
  static fromTransport(error: any): ApiError {
    const envelope = error?.response?.data as ResultEnvelope<unknown> | undefined
    if (isResultEnvelope(envelope)) {
      return ApiError.fromEnvelope(envelope)
    }
    const status = error?.response?.status ?? 0
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return new ApiError(message, { status, cause: error })
  }
}

/**
 * Detect a convention4j Result envelope. A raw payload might coincidentally
 * carry a numeric `status`, so we also require the string `code` field that
 * every Result always includes.
 */
export function isResultEnvelope(data: unknown): data is ResultEnvelope<unknown> {
  if (!data || typeof data !== 'object') {
    return false
  }
  const candidate = data as Record<string, unknown>
  return typeof candidate.status === 'number' && typeof candidate.code === 'string'
}
