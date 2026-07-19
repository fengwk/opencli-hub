import type { CommandArgument, HubCommand } from '@/features/commands/types'

function requiredArgumentPlaceholder(argument: CommandArgument): string {
  return `<${argument.name}>`
}

/** Builds the minimal argv accepted by a catalog command, leaving operator values as JSON-safe placeholders. */
export function buildRequiredCommandArgv(command: Pick<HubCommand, 'site' | 'name' | 'args'>): string[] {
  const argv = [command.site, command.name]
  for (const argument of command.args ?? []) {
    if (!argument.required) continue
    if (argument.positional) {
      argv.push(requiredArgumentPlaceholder(argument))
      continue
    }
    argv.push(`--${argument.name}`)
    if (argument.valueRequired) argv.push(requiredArgumentPlaceholder(argument))
  }
  return argv
}

/** Produces a copyable shell template for the Hub's controlled execution endpoint. */
export function buildCommandCurlTemplate(command: Pick<HubCommand, 'site' | 'name' | 'args'>): string {
  const payload = JSON.stringify({
    instanceId: '<INSTANCE_ID>',
    argv: buildRequiredCommandArgv(command),
    timeoutMillis: 600000,
  }, null, 2)

  return `# Replace <HUB_URL>, <INSTANCE_ID>, and every required argument placeholder.
# Add Gateway authentication using your deployment method; do not put credentials in this template.
curl --fail --show-error --request POST \\
  "<HUB_URL>/api/opencli/execute" \\
  --header 'Content-Type: application/json' \\
  --data @- <<'JSON'
${payload}
JSON`
}
