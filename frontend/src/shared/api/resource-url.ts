import { apiBaseUrl } from '@/shared/api/client'

/**
 * A resource lives under the controlled virtual path
 * `/api/resources/{date}/{group}/{relativePath}`. `relativePath` may contain
 * nested segments separated by `/`. Each segment is encoded independently so
 * that reserved/unsafe characters cannot break out of the intended path.
 */
export interface ResourceLocation {
  date: string
  group: string
  relativePath?: string | null
}

/** Encode a single path segment (never emits a `/`). */
function encodeSegment(segment: string): string {
  return encodeURIComponent(segment)
}

/**
 * Encode a possibly nested relative path, preserving `/` separators between
 * segments while encoding each segment. Empty segments (leading/trailing or
 * duplicate slashes) are dropped.
 */
export function encodeResourceRelativePath(relativePath: string): string {
  return relativePath
    .split('/')
    .filter((segment) => segment.length > 0)
    .map(encodeSegment)
    .join('/')
}

/** Build the encoded path portion `{date}/{group}[/{relativePath}]`. */
export function buildResourcePath(location: ResourceLocation): string {
  const base = `${encodeSegment(location.date)}/${encodeSegment(location.group)}`
  const relative = location.relativePath ? encodeResourceRelativePath(location.relativePath) : ''
  return relative ? `${base}/${relative}` : base
}

/**
 * Build a fully-qualified resource URL under the API base. Optional query
 * parameters are appended and encoded; callers decide semantics (e.g. inline
 * vs. download) without this helper hard-coding endpoint conventions.
 */
export function buildResourceUrl(
  location: ResourceLocation,
  query?: Record<string, string | number | boolean>,
): string {
  const url = `${apiBaseUrl}/resources/${buildResourcePath(location)}`
  if (!query) {
    return url
  }
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    search.append(key, String(value))
  }
  const queryString = search.toString()
  return queryString ? `${url}?${queryString}` : url
}
