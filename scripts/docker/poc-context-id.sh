#!/usr/bin/env bash
#
# poc-context-id.sh
#
# 从 OpenCLI daemon 的 /status JSON 中提取已连接 Browser Bridge 的 contextId 列表。
# 默认只输出第一个 contextId（方便脚本捕获）；加 `-a` 输出全部。

set -euo pipefail

DAEMON_HOST="${OPENCLI_DAEMON_HOST:-127.0.0.1}"
DAEMON_PORT="${OPENCLI_DAEMON_PORT:-19825}"
BASE_URL="http://${DAEMON_HOST}:${DAEMON_PORT}"

if ! command -v jq >/dev/null 2>&1; then
    echo "error: jq is required for poc-context-id.sh" >&2
    exit 2
fi

TMP="$(mktemp)"
trap 'rm -f "${TMP}"' EXIT

HTTP_CODE="$(curl -s -o "${TMP}" -w '%{http_code}' -H 'X-OpenCLI: 1' "${BASE_URL}/status" || echo 000)"
if [[ "${HTTP_CODE}" != "200" ]]; then
    echo "GET ${BASE_URL}/status -> HTTP ${HTTP_CODE}" >&2
    exit 1
fi

if [[ "${1:-}" == "-a" || "${1:-}" == "--all" ]]; then
    CONTEXT_IDS="$(jq -r '.profiles[]?.contextId // empty' "${TMP}")"
else
    CONTEXT_IDS="$(jq -r '.profiles[0].contextId // empty' "${TMP}")"
fi

if [[ -z "${CONTEXT_IDS}" ]]; then
    echo "no Browser Bridge contextId is connected" >&2
    exit 1
fi

printf '%s\n' "${CONTEXT_IDS}"
