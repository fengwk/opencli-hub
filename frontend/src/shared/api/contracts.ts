/**
 * Shared API contract types.
 *
 * The backend uses the convention4j `Result` envelope for every response:
 * `{ status, code, message, data, errors }` where `status` mirrors the HTTP
 * status code. These types describe the transport shape only; feature-specific
 * DTOs live alongside their features.
 */

export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
  errors?: Record<string, unknown> | null
}

export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalCount: number | string
  results: T[]
}

/**
 * Backend `LocalDateTime` values are serialized either as ISO strings or, under
 * some Jackson configurations, as numeric arrays. Features normalize as needed.
 */
export type BackendDateTime = string | number[] | null
