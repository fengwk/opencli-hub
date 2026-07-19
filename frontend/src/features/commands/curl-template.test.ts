import { describe, expect, it } from 'vitest'
import { buildCommandCurlTemplate, buildRequiredCommandArgv } from '@/features/commands/curl-template'
import type { HubCommand } from '@/features/commands/types'

const command: Pick<HubCommand, 'site' | 'name' | 'args'> = {
  site: 'chatgpt-agent',
  name: 'ask',
  args: [
    {
      name: 'prompt',
      type: 'string',
      required: true,
      valueRequired: true,
      positional: true,
      choices: null,
      defaultValue: null,
      help: null,
    },
    {
      name: 'model',
      type: 'string',
      required: true,
      valueRequired: true,
      positional: false,
      choices: null,
      defaultValue: null,
      help: null,
    },
    {
      name: 'stream',
      type: 'boolean',
      required: true,
      valueRequired: false,
      positional: false,
      choices: null,
      defaultValue: null,
      help: null,
    },
    {
      name: 'session',
      type: 'string',
      required: false,
      valueRequired: true,
      positional: false,
      choices: null,
      defaultValue: null,
      help: null,
    },
  ],
}

describe('command curl template', () => {
  it('includes only required positional, valued, and flag arguments in catalog order', () => {
    expect(buildRequiredCommandArgv(command)).toEqual([
      'chatgpt-agent',
      'ask',
      '<prompt>',
      '--model',
      '<model>',
      '--stream',
    ])
  })

  it('uses the controlled Hub endpoint with replaceable non-secret placeholders', () => {
    const template = buildCommandCurlTemplate(command)

    expect(template).toContain('"<HUB_URL>/api/opencli/execute"')
    expect(template).toContain('"instanceId": "<INSTANCE_ID>"')
    expect(template).toContain('"<prompt>"')
    expect(template).toContain('"--model"')
    expect(template).not.toContain('"--session"')
    expect(template).toContain("--data @- <<'JSON'")
  })
})
