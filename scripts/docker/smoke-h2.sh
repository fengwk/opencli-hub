#!/usr/bin/env bash
# Deterministic container smoke checks only. This does not create an Instance
# and therefore does not claim a Chrome/OpenCLI browser E2E result.
set -Eeuo pipefail

readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly project_dir="$(cd "${script_dir}/../.." && pwd)"
readonly compose_file="${COMPOSE_FILE:-compose.h2.yml}"
readonly compose_project="${OPENCLI_HUB_SMOKE_PROJECT:-opencli-hub-smoke-${UID}}"
readonly service="${HUB_SERVICE:-hub}"
readonly host_port="${OPENCLI_HUB_SMOKE_PORT:-18080}"
readonly base_url="${OPENCLI_HUB_SMOKE_URL:-http://127.0.0.1:${host_port}}"
readonly build_mode="${OPENCLI_HUB_SMOKE_BUILD_MODE:-docker}"
readonly configured_signing_key="${OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE:-.secrets/opencli-extension-signing-key.pem}"
if [[ "${configured_signing_key}" == /* ]]; then
    readonly signing_key_file="${configured_signing_key}"
else
    readonly signing_key_file="${project_dir}/${configured_signing_key}"
fi
export OPENCLI_HUB_HOST_PORT="${host_port}"
export OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE="${signing_key_file}"

run_compose() {
    docker compose --project-name "${compose_project}" -f "${compose_file}" "$@"
}

cleanup() {
    if [[ "${KEEP_SMOKE_STACK:-0}" != "1" ]]; then
        run_compose down --volumes --remove-orphans
    fi
}

if [[ ! "${host_port}" =~ ^[1-9][0-9]*$ ]] || (( 10#${host_port} > 65535 )); then
    printf 'OPENCLI_HUB_SMOKE_PORT must be between 1 and 65535: %s\n' "${host_port}" >&2
    exit 2
fi
[[ -r "${signing_key_file}" && -s "${signing_key_file}" ]] || {
    printf 'OPENCLI_HUB_EXTENSION_SIGNING_KEY_FILE must be readable and nonempty: %s\n' \
        "${signing_key_file}" >&2
    exit 1
}
trap cleanup EXIT

cd "${project_dir}"
case "${build_mode}" in
    docker)
        run_compose up --build --detach
        ;;
    local)
        "${script_dir}/build-local.sh" opencli-hub:local
        run_compose up --detach
        ;;
    *)
        printf 'Unsupported OPENCLI_HUB_SMOKE_BUILD_MODE: %s\n' "${build_mode}" >&2
        exit 2
        ;;
esac
for _ in $(seq 1 60); do
    if curl --fail --silent "${base_url}/actuator/health" >/dev/null; then
        break
    fi
    sleep 1
done
curl --fail --silent --show-error "${base_url}/actuator/health" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
curl --fail --silent --show-error "${base_url}/api/instances" >/dev/null
run_compose exec -T "${service}" sh -ec '
    expected="$(jq -er ".cli.version" /opt/opencli/artifact-build-info.json)"
    actual="$(opencli --version | head -n1 | tr -d "[:space:]")"
    test -n "${expected}"
    test "${actual}" = "${expected}"
    test "$(git config --system --get http.version)" = "HTTP/1.1"
'
run_compose exec -T "${service}" curl --fail --silent http://127.0.0.1:18181/healthz | grep -Fxq 'ok'
printf 'H2 smoke passed (project=%s, host-port=%s). No Chrome E2E was run.\n' \
    "${compose_project}" "${host_port}"
