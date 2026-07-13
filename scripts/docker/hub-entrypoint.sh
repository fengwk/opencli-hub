#!/usr/bin/env bash
#
# Production Hub entrypoint. It owns only the fixed loopback CRX update server
# and the Java Hub process. Browser/Xvfb/VNC/OpenCLI daemon lifecycle belongs to
# the Hub application and must not be started here.
set -Eeuo pipefail

readonly HUB_DATA_DIR="${OPENCLI_HUB_DATA_DIR:-/data/opencli-hub}"
readonly SHUTDOWN_TIMEOUT_SECONDS="${OPENCLI_HUB_SHUTDOWN_TIMEOUT_SECONDS:-30}"
readonly CRX_DIR=/opt/opencli/crx
readonly POLICY_FILE=/etc/opt/chrome/policies/managed/opencli-hub-extension.json
readonly CRX_PORT=18181
readonly CRX_BASE_URL="http://127.0.0.1:${CRX_PORT}"
readonly CRX_LOG="${HUB_DATA_DIR}/logs/crx-http-server.log"
readonly HUB_LOG="${HUB_DATA_DIR}/logs/hub-console.log"
readonly HUB_JAR=/opt/opencli-hub/opencli-hub.jar

crx_pid=""
hub_pid=""
shutting_down=0

log() {
    printf '[hub-entrypoint] %s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

require_readable_file() {
    local file="$1"
    [[ -r "${file}" && -s "${file}" ]] || {
        log "required asset is absent or unreadable: ${file}"
        exit 1
    }
}

validate_assets() {
    local extension_id policy_url
    require_readable_file "${CRX_DIR}/extension.crx"
    require_readable_file "${CRX_DIR}/updates.xml"
    require_readable_file "${CRX_DIR}/build-info.json"
    require_readable_file "${POLICY_FILE}"
    require_readable_file "${HUB_JAR}"

    extension_id="$(jq -er '.extensionId | select(type == "string" and test("^[a-p]{32}$"))' "${CRX_DIR}/build-info.json")"
    grep -Fq '__UPDATE_BASE__/extension.crx' "${CRX_DIR}/updates.xml" || {
        log "CRX update manifest does not contain the fixed extension artifact"
        exit 1
    }
    policy_url="${CRX_BASE_URL}/updates.xml"
    jq -e --arg id "${extension_id}" --arg url "${policy_url}" '
        .ExtensionInstallForcelist == [$id + ";" + $url]
        and .ExtensionSettings[$id].installation_mode == "force_installed"
        and .ExtensionSettings[$id].update_url == $url
        and .ExtensionSettings[$id].override_update_url == true
    ' "${POLICY_FILE}" >/dev/null || {
        log "managed Chrome policy does not match fixed CRX asset ${extension_id}"
        exit 1
    }
}

stop_process() {
    local name="$1" pid="$2" signal="$3"
    [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null || return 0
    log "stopping ${name} (pid=${pid}, signal=${signal})"
    kill "-${signal}" "${pid}" 2>/dev/null || true
    local waited=0
    while kill -0 "${pid}" 2>/dev/null && (( waited < SHUTDOWN_TIMEOUT_SECONDS * 10 )); do
        sleep 0.1
        waited=$((waited + 1))
    done
    if kill -0 "${pid}" 2>/dev/null; then
        log "${name} did not stop in ${SHUTDOWN_TIMEOUT_SECONDS} seconds; sending KILL"
        kill -KILL "${pid}" 2>/dev/null || true
    fi
    wait "${pid}" 2>/dev/null || true
}

cleanup() {
    local signal="${1:-TERM}"
    (( shutting_down == 0 )) || return 0
    shutting_down=1
    stop_process "Hub" "${hub_pid}" "${signal}"
    stop_process "CRX HTTP server" "${crx_pid}" "${signal}"
}

on_signal() {
    local signal="$1"
    log "received ${signal}"
    cleanup "${signal}"
    trap - EXIT
    exit 0
}

trap 'on_signal TERM' TERM
trap 'on_signal INT' INT
trap 'on_signal HUP' HUP
trap 'cleanup TERM' EXIT

[[ "${HUB_DATA_DIR}" == /* ]] || {
    log "OPENCLI_HUB_DATA_DIR must be an absolute path"
    exit 2
}
[[ "${SHUTDOWN_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]] || {
    log "OPENCLI_HUB_SHUTDOWN_TIMEOUT_SECONDS must be a positive integer"
    exit 2
}

mkdir -p "${HUB_DATA_DIR}/logs" "${HUB_DATA_DIR}/database" \
    "${HUB_DATA_DIR}/resources" "${HUB_DATA_DIR}/instances" "${HOME}/data"
validate_assets

# These values intentionally override the environment: the managed policy and
# the CRX server are a fixed, loopback-only pair.
export OPENCLI_CRX_HTTP_PORT="${CRX_PORT}"
export OPENCLI_UPDATE_BASE_URL="${CRX_BASE_URL}"
log "starting fixed loopback CRX HTTP server on ${CRX_BASE_URL}"
node /opt/opencli/scripts/crx-http-server.mjs \
    "${CRX_DIR}/extension.crx" "${CRX_DIR}/updates.xml" "${CRX_DIR}/build-info.json" \
    >>"${CRX_LOG}" 2>&1 &
crx_pid=$!

for _ in $(seq 1 50); do
    if curl --fail --silent --show-error --connect-timeout 1 "${CRX_BASE_URL}/healthz" >/dev/null; then
        break
    fi
    if ! kill -0 "${crx_pid}" 2>/dev/null; then
        log "CRX HTTP server exited before becoming ready; log follows"
        tail -n 50 "${CRX_LOG}" >&2 || true
        exit 1
    fi
    sleep 0.1
done
curl --fail --silent --show-error --connect-timeout 1 "${CRX_BASE_URL}/healthz" >/dev/null || {
    log "CRX HTTP server did not become healthy"
    exit 1
}

# Deliberately parse JAVA_OPTS as whitespace-delimited JVM options and execute
# the resulting array without eval or a shell command string. Put Spring args
# after the entrypoint command, e.g. `... hub-entrypoint.sh --server.port=8081`.
java_opts=()
if [[ -n "${JAVA_OPTS:-}" ]]; then
    read -r -a java_opts <<<"${JAVA_OPTS}"
fi
log "starting Java Hub"
java "${java_opts[@]}" -jar "${HUB_JAR}" "$@" >>"${HUB_LOG}" 2>&1 &
hub_pid=$!

# Either child exiting is terminal: no stale CRX server or orphan Hub remains.
if wait -n "${crx_pid}" "${hub_pid}"; then
    child_status=0
else
    child_status=$?
fi
log "a supervised process exited with status ${child_status}"
exit "${child_status}"
