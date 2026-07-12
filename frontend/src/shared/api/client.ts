import axios from 'axios'
import type { ResultEnvelope } from '@/shared/api/contracts'
import { ApiError, isResultEnvelope } from '@/shared/api/errors'

export interface HttpClient {
  get<T>(url: string, config?: { params?: Record<string, unknown> }): Promise<T>
  post<T>(url: string, data?: unknown): Promise<T>
  put<T>(url: string, data?: unknown): Promise<T>
  delete<T>(url: string): Promise<T>
}

export const apiBaseUrl = '/api'

const axiosClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 60000,
})

axiosClient.interceptors.response.use(
  (response) => {
    const envelope = response.data as ResultEnvelope<unknown>
    // Non-envelope payloads (e.g. binary/plain responses) pass through as-is.
    if (!isResultEnvelope(envelope)) {
      return response.data
    }
    // `status` mirrors the HTTP status; anything outside 2xx is a failure.
    if (envelope.status < 200 || envelope.status >= 300) {
      return Promise.reject(ApiError.fromEnvelope(envelope))
    }
    return envelope.data
  },
  (error) => Promise.reject(ApiError.fromTransport(error)),
)

export const apiClient: HttpClient = {
  get: <T>(url: string, config?: { params?: Record<string, unknown> }) =>
    axiosClient.get(url, config) as Promise<T>,
  post: <T>(url: string, data?: unknown) => axiosClient.post(url, data) as Promise<T>,
  put: <T>(url: string, data?: unknown) => axiosClient.put(url, data) as Promise<T>,
  delete: <T>(url: string) => axiosClient.delete(url) as Promise<T>,
}
