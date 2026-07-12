#!/usr/bin/env bash
#
# poc-status.sh
#
# 打印 PoC 当前 daemon 与 extension 状态。等价于 `opencli daemon status`，
# 但增加 /status JSON 输出，方便脚本判断。

set -euo pipefail

DAEMON_HOST="${OPENCLI_DAEMON_HOST:-127.0.0.1}"
DAEMON_PORT="${OPENCLI_DAEMON_PORT:-19825}"
BASE_URL="http://${DAEMON_HOST}:${DAEMON_PORT}"

if ! command -v jq >/dev/null 2>&1; then
    echo "error: jq is required for poc-status.sh" >&2
    exit 2
fi

echo "== opencli daemon status =="
if opencli daemon status; then
    :
else
    echo "opencli daemon status command failed; falling back to /status"
fi

echo
echo "== /status json =="
HTTP_CODE="$(curl -s -o /tmp/opencli_status.$$ -w '%{http_code}' -H 'X-OpenCLI: 1' "${BASE_URL}/status" || echo 000)"
if [[ "${HTTP_CODE}" != "200" ]]; then
    echo "GET ${BASE_URL}/status -> HTTP ${HTTP_CODE}" >&2
    rm -f /tmp/opencli_status.$$
    exit 1
fi
jq . /tmp/opencli_status.$$
rm -f /tmp/opencli_status.$$
