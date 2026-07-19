import { describe, expect, it } from 'vitest'
import { formatBackendDateTime } from '@/shared/api/backend-date-time'
import { formatMillis, formatStdout } from '@/features/executions/execution-format'

describe('execution-format', () => {
  it('formats convention4j long strings and supported LocalDateTime forms', () => {
    expect(formatMillis('141738')).toBe('141738 ms')
    expect(formatMillis(141738)).toBe('141738 ms')
    expect(formatBackendDateTime('2026-07-17T18:47:25.241526177')).toBe('2026-07-17 18:47:25.241526177')
    expect(formatBackendDateTime([2026, 7, 17, 18, 47, 25, 241526177])).toBe('2026-07-17 18:47:25')
  })

  it('renders omitted or incomplete lifecycle timestamps safely', () => {
    expect(formatBackendDateTime(undefined)).toBe('—')
    expect(formatBackendDateTime(null)).toBe('—')
    expect(formatBackendDateTime([])).toBe('—')
  })

  it('pretty-prints JSON stdout and preserves plain text', () => {
    expect(formatStdout('{"a":1}')).toBe('{\n  "a": 1\n}')
    expect(formatStdout('not json')).toBe('not json')
    expect(formatStdout(null)).toBe('')
  })
})
