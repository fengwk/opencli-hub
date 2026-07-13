#!/usr/bin/env bash
# Deterministic container smoke checks only. This does not create an Instance
# and therefore does not claim a Chrome/OpenCLI browser E2E result.
set -Eeuo pipefail

readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly project_dir="$(cd "${script_dir}/../.." && pwd)"
readonly compose_file="${COMPOSE_FILE:-compose.h2.yml}"
readonly service="${HUB_SERVICE:-hub}"
readonly base_url="${OPENCLI_HUB_SMOKE_URL:-http://127.0.0.1:8080}"
readonly build_mode="${OPENCLI_HUB_SMOKE_BUILD_MODE:-docker}"

cleanup() {
    if [[ "${KEEP_SMOKE_STACK:-0}" != "1" ]]; then
        docker compose -f "${compose_file}" down --volumes --remove-orphans
    fi
}
trap cleanup EXIT

cd "${project_dir}"
case "${build_mode}" in
    docker)
        docker compose -f "${compose_file}" up --build --detach
        ;;
    local)
        "${script_dir}/build-local.sh" opencli-hub:local
        docker compose -f "${compose_file}" up --detach
        ;;
    *)
        printf 'Unsupported OPENCLI_HUB_SMOKE_BUILD_MODE: %s\n' "${build_mode}" >&2
        exit 2
        ;;
esac
for _ in $(seq 1 60); do
    if curl --fail --silent --show-error "${base_url}/actuator/health" >/dev/null; then
        break
    fi
    sleep 1
done
curl --fail --silent --show-error "${base_url}/actuator/health" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
curl --fail --silent --show-error "${base_url}/api/instances" >/dev/null
docker compose -f "${compose_file}" exec -T "${service}" opencli --version | grep -Fq '1.8.6'
docker compose -f "${compose_file}" exec -T "${service}" curl --fail --silent http://127.0.0.1:18181/healthz | grep -Fxq 'ok'
printf 'H2 container health/API/OpenCLI-version/CRX-server smoke checks passed. No Chrome E2E was run.\n'
