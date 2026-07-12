import { describe, expect, it } from 'vitest'
import {
  buildResourcePath,
  buildResourceUrl,
  encodeResourceRelativePath,
} from '@/shared/api/resource-url'

describe('resource url helper', () => {
  it('encodes each path segment independently', () => {
    // Spaces and reserved characters must be percent-encoded per segment.
    expect(
      buildResourcePath({
        date: '2026-07-12',
        group: 'execution 9001',
        relativePath: 'sub dir/result #1.png',
      }),
    ).toBe('2026-07-12/execution%209001/sub%20dir/result%20%231.png')
  })

  it('prevents path traversal characters from breaking out of the path', () => {
    // `..` and `/` inside a group value are encoded, never emitted raw.
    const path = buildResourcePath({ date: '2026-07-12', group: '../etc', relativePath: 'a/b' })
    expect(path).toBe('2026-07-12/..%2Fetc/a/b')
    expect(path).not.toContain('/../')
  })

  it('drops empty relative-path segments from duplicate or edge slashes', () => {
    expect(encodeResourceRelativePath('/a//b/')).toBe('a/b')
    expect(buildResourcePath({ date: 'd', group: 'g', relativePath: '' })).toBe('d/g')
    expect(buildResourcePath({ date: 'd', group: 'g' })).toBe('d/g')
  })

  it('builds a full API url and appends encoded query parameters', () => {
    expect(
      buildResourceUrl({ date: '2026-07-12', group: 'g', relativePath: 'f.png' }),
    ).toBe('/api/resources/2026-07-12/g/f.png')

    expect(
      buildResourceUrl(
        { date: '2026-07-12', group: 'g', relativePath: 'f.png' },
        { disposition: 'attachment' },
      ),
    ).toBe('/api/resources/2026-07-12/g/f.png?disposition=attachment')
  })
})
